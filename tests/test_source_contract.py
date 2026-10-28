"""Structural regression checks only; do not replace Android build/device tests."""
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java'

class SourceContract(unittest.TestCase):
    def test_manifest_exposes_only_launcher_and_basic_permissions(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        permissions = {e.attrib[ns+'name'] for e in manifest.findall('uses-permission')}
        self.assertEqual(permissions, {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
            'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
            'android.permission.POST_NOTIFICATIONS'})
        app = manifest.find('application')
        self.assertEqual(app.attrib[ns+'allowBackup'], 'false')
        self.assertEqual(app.attrib[ns+'usesCleartextTraffic'], 'false')
        services = app.findall('service')
        self.assertEqual(len(services), 1)
        self.assertEqual(services[0].attrib[ns+'exported'], 'false')
    def test_no_remote_mutation_or_shell_entrypoints(self):
        source = '\n'.join(p.read_text() for p in SRC.rglob('*.kt'))
        for forbidden in ['Runtime.getRuntime', 'ProcessBuilder(', 'UA_Client_write', 'UA_Client_call', 'ChannelExec', 'setProcessDefaultNetwork', 'bindProcessToNetwork']:
            self.assertNotIn(forbidden, source)
    def test_protocol_is_read_only_and_transport_is_pinned_tls(self):
        proto = (ROOT / 'app/src/main/proto/sfa_link.proto').read_text()
        import re
        self.assertEqual(re.findall(r'rpc (\w+)\(', proto), ['Hello', 'ListSignals', 'Subscribe'])
        source = '\n'.join(p.read_text() for p in SRC.rglob('*.kt'))
        for forbidden in ['usePlaintext', 'TrustAll', 'InsecureTrustManagerFactory', 'ALLOW_ALL_HOSTNAME_VERIFIER']:
            self.assertNotIn(forbidden, source)
        client = (SRC / 'it/spaceanalyzer/mobile/link/SfaLinkClient.kt').read_text()
        self.assertIn('.useTransportSecurity()', client)
        self.assertIn('pinnedSocketFactory(pinnedFingerprint)', client)
        # The only trust manager that accepts any certificate is the pairing probe, which sends no data.
        tls = (SRC / 'it/spaceanalyzer/mobile/link/PinnedTls.kt').read_text()
        self.assertEqual(tls.count('X509TrustManager {'), 2)
    def test_offline_ethernet_binding_precedes_connect(self):
        source = (SRC / 'it/spaceanalyzer/mobile/networking/NetworkRepository.kt').read_text()
        self.assertNotIn('.clearCapabilities()', source)
        for capability in ['NOT_RESTRICTED', 'TRUSTED', 'NOT_VPN']:
            self.assertIn(f'.removeCapability(NetworkCapabilities.NET_CAPABILITY_{capability})', source)
        self.assertLess(source.index('network.bindSocket(socket)'), source.index('socket.connect('))
        self.assertIn('Socket().use', source)
        self.assertIn('awaitClose { manager.unregisterNetworkCallback(callback) }', source)
    def test_no_embedded_credentials_or_fake_apk(self):
        for pattern in ['*.apk', '*.jks', '*.keystore', '*.p12']:
            self.assertEqual(list((ROOT/'app/src').rglob(pattern)), [])
    def test_android10_and_ci_build_are_declared(self):
        build = (ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('minSdk = 29', build)
        self.assertIn('arm64-v8a', build)
        self.assertIn('io.grpc:grpc-okhttp', build)
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('testDebugUnitTest lintDebug assembleDebug', workflow)

if __name__ == '__main__': unittest.main()
