using System.Threading.Channels;
using Grpc.Core;
using SpaceAnalyzer.App.MobileLink.Proto;
using ProtoSample = SpaceAnalyzer.App.MobileLink.Proto.Sample;

namespace SpaceLinkBridge.Link;

public sealed record SignalDefinition(uint Id, string NodeId, string Name, string Unit);

/// <summary>One value read from Space: quality is already an OPC UA style code (0 = good).</summary>
public readonly record struct LinkSample(string NodeId, double? Value, uint Quality, long TimestampUs);

/// <summary>
/// Bridge between the data read from Space and the phone: numeric samples are copied into numbered batches
/// (kept for a few minutes so a phone that reconnects resumes without a hole) and handed to the
/// subscribers. Publishing never blocks: a slow phone loses its oldest
/// batches, which it detects from the sequence numbers.
/// </summary>
public sealed class MobileLinkHub
{
    public const int HistoryBatches = 6000;
    public const long HistorySamples = 1_500_000;
    const int SubscriberQueue = 4000;
    const string SignalsChanged = "Elenco dei segnali cambiato: riconnettere";

    readonly object gate = new();
    readonly Dictionary<string, uint> ids = new();
    readonly Dictionary<uint, SignalDefinition> definitions = new();
    HashSet<uint> active = new();
    readonly List<uint> order = new();
    readonly LinkedList<SampleBatch> history = new();
    readonly List<Subscriber> subscribers = new();
    long historySamples;
    long sequence;
    uint lastId;

    /// <summary>Publishing is a no-op until the server is running.</summary>
    public volatile bool Active;

    public long Sequence { get { lock (gate) return sequence; } }
    public int SubscriberCount { get { lock (gate) return subscribers.Count; } }

    /// <summary>Signals available in Space: the phones are reconnected when the list or a name changes.</summary>
    public void SetSignals(IEnumerable<(string NodeId, string Name, string Unit)> selected)
    {
        lock (gate)
        {
            var next = new HashSet<uint>();
            var nextOrder = new List<uint>();
            var changed = false;
            foreach (var (nodeId, name, unit) in selected)
            {
                var id = IdFor(nodeId);
                var definition = new SignalDefinition(id, nodeId, name, unit);
                if (!definitions.TryGetValue(id, out var old) || old != definition) changed = true;
                definitions[id] = definition;
                if (next.Add(id)) nextOrder.Add(id);
            }
            if (!next.SetEquals(active)) changed = true;
            active = next;
            order.Clear();
            order.AddRange(nextOrder);
            if (changed) AbortLocked(SignalsChanged);
        }
    }

    public IReadOnlyList<SignalDefinition> Signals()
    {
        lock (gate) return order.Select(id => definitions[id]).ToList();
    }

    uint IdFor(string nodeId)
    {
        if (!ids.TryGetValue(nodeId, out var id)) { id = ++lastId; ids[nodeId] = id; }
        return id;
    }

    /// <summary>Copies the samples of the signals set with <see cref="SetSignals"/> into a new numbered batch.</summary>
    public void Publish(IReadOnlyList<LinkSample> rows)
    {
        if (!Active || rows.Count == 0) return;
        var batch = new SampleBatch();
        lock (gate)
        {
            foreach (var row in rows)
            {
                if (row.Value is not double value || !double.IsFinite(value)) continue;
                if (!ids.TryGetValue(row.NodeId, out var id) || !active.Contains(id)) continue;
                batch.Samples.Add(new ProtoSample { SignalId = id, TimestampUs = row.TimestampUs, Value = value, Quality = row.Quality });
            }
            if (batch.Samples.Count == 0) return;
            batch.Seq = (ulong)++sequence;
            history.AddLast(batch);
            historySamples += batch.Samples.Count;
            while (history.Count > 1 && (history.Count > HistoryBatches || historySamples > HistorySamples))
            {
                historySamples -= history.First!.Value.Samples.Count;
                history.RemoveFirst();
            }
            foreach (var subscriber in subscribers) subscriber.Channel.Writer.TryWrite(batch);
        }
    }

    /// <summary>Ends every stream with UNAVAILABLE so the phones reconnect (new access code, new signal list).</summary>
    public void AbortAll(string reason)
    {
        lock (gate) AbortLocked(reason);
    }

    void AbortLocked(string reason)
    {
        foreach (var subscriber in subscribers)
            subscriber.Channel.Writer.TryComplete(new RpcException(new Status(StatusCode.Unavailable, reason)));
        subscribers.Clear();
    }

    /// <summary>Drops the history and ends the streams (server stopped).</summary>
    public void Reset()
    {
        lock (gate)
        {
            AbortLocked("Servizio fermato");
            history.Clear();
            historySamples = 0;
        }
    }

    public Subscription Subscribe(IReadOnlySet<uint>? filter, ulong resumeAfter)
    {
        lock (gate)
        {
            var channel = Channel.CreateBounded<SampleBatch>(new BoundedChannelOptions(SubscriberQueue)
            { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true, SingleWriter = false });
            var backlog = new List<SampleBatch>();
            var gap = false;
            if (resumeAfter > 0)
            {
                // Resume point ahead of the counter: the server restarted and numbers began again.
                if (resumeAfter > (ulong)sequence) gap = true;
                else
                {
                    backlog.AddRange(history.Where(b => b.Seq > resumeAfter));
                    var oldest = history.First?.Value.Seq ?? (ulong)sequence + 1;
                    if (oldest > resumeAfter + 1 && (ulong)sequence > resumeAfter) gap = true;
                }
            }
            var subscriber = new Subscriber(channel);
            subscribers.Add(subscriber);
            return new Subscription(this, subscriber, backlog, filter, gap);
        }
    }

    internal sealed class Subscriber(Channel<SampleBatch> channel)
    {
        public Channel<SampleBatch> Channel { get; } = channel;
    }

    public sealed class Subscription : IDisposable
    {
        readonly MobileLinkHub hub;
        readonly Subscriber subscriber;
        readonly List<SampleBatch> backlog;
        readonly IReadOnlySet<uint>? filter;
        bool gap;

        internal Subscription(MobileLinkHub hub, Subscriber subscriber, List<SampleBatch> backlog, IReadOnlySet<uint>? filter, bool gap)
        {
            this.hub = hub; this.subscriber = subscriber; this.backlog = backlog; this.filter = filter; this.gap = gap;
        }

        public async IAsyncEnumerable<SampleBatch> ReadAllAsync([System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct)
        {
            foreach (var batch in backlog)
                yield return Prepare(batch);
            backlog.Clear();
            await foreach (var batch in subscriber.Channel.Reader.ReadAllAsync(ct))
                yield return Prepare(batch);
        }

        SampleBatch Prepare(SampleBatch batch)
        {
            var result = batch;
            if (filter is { Count: > 0 })
            {
                result = new SampleBatch { Seq = batch.Seq };
                foreach (var sample in batch.Samples) if (filter.Contains(sample.SignalId)) result.Samples.Add(sample);
                // Empty batches are still sent: the sequence stays contiguous, so a jump always means lost data.
            }
            if (gap)
            {
                if (ReferenceEquals(result, batch)) result = batch.Clone();
                result.Gap = true;
                gap = false;
            }
            return result;
        }

        public void Dispose()
        {
            lock (hub.gate) hub.subscribers.Remove(subscriber);
            subscriber.Channel.Writer.TryComplete();
        }
    }
}
