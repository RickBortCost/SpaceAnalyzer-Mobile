using Grpc.Core;
using SpaceAnalyzer.App.MobileLink.Proto;

namespace SpaceLinkBridge.Link;

/// <summary>SFA Link v1 (docs/PROTOCOL.md). Read-only: no call changes Space.</summary>
public sealed class SfaLinkService(MobileLinkHub hub, Func<string> acquisitionName) : SfaLink.SfaLinkBase
{
    public override Task<HelloReply> Hello(HelloRequest request, ServerCallContext context) =>
        Task.FromResult(new HelloReply
        {
            ProtocolVersion = 1,
            ServerName = Environment.MachineName,
            AcquisitionName = acquisitionName(),
        });

    public override Task<ListSignalsReply> ListSignals(ListSignalsRequest request, ServerCallContext context)
    {
        var reply = new ListSignalsReply();
        foreach (var signal in hub.Signals())
            reply.Signals.Add(new Signal { Id = signal.Id, Name = signal.Name, Unit = signal.Unit, Type = SignalType.Double });
        return Task.FromResult(reply);
    }

    public override async Task Subscribe(SubscribeRequest request, IServerStreamWriter<SampleBatch> responseStream, ServerCallContext context)
    {
        var filter = request.SignalIds.Count == 0 ? null : request.SignalIds.ToHashSet();
        using var subscription = hub.Subscribe(filter, request.ResumeAfterSeq);
        try
        {
            await foreach (var batch in subscription.ReadAllAsync(context.CancellationToken))
                await responseStream.WriteAsync(batch, context.CancellationToken);
        }
        catch (OperationCanceledException) { }
    }
}
