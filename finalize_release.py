from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

base = Path(__file__).resolve().parent
out = base.parents[1] / 'outputs'
apk = base / 'probe/app/build/outputs/apk/debug/app-debug.apk'
tester_apk = base / 'probe/tester/build/outputs/apk/debug/tester-debug.apk'
module = out / 'XiaomiEsimLPA-HyperOS4-bixi-v0.4.0.zip'
original_sha = '9daa20d489168264109d68532d27857a9eef7d7ce72199c565afb6b96d2e6b4f'
sha = lambda data: hashlib.sha256(data).hexdigest()

with zipfile.ZipFile(module) as z:
    assert z.testzip() is None
    assert z.read('config') == b'MODE=off\n'
    assert 'system.prop' not in z.namelist()
    assert b'tehcneko' not in z.read('module.prop').lower()
    assert z.read('compat.apk') == apk.read_bytes()
    assert z.read('tester.apk') == tester_apk.read_bytes()
    assert z.read('lpa.apk') == z.read('system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk')
    assert sha(z.read('lpa.apk')) == original_sha
    for name in z.namelist():
        if name.endswith('.xml'):
            ET.fromstring(z.read(name))
        if name.endswith('.sh'):
            assert b'\r' not in z.read(name)

with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    scope = z.read('META-INF/xposed/scope.list').decode().splitlines()
    assert sorted(scope) == ['com.android.phone', 'com.miui.euicc']


def values(file, suffix):
    return [
        n.get('text', '')
        for n in ET.parse(base / file).iter('node')
        if n.get('resource-id', '').endswith(suffix)
    ]


if (base / 'final-restored-list.xml').exists():
    assert values('final-restored-list.xml', 'profile_item_title') == ['Bootstrap', 'RedteaGO', 'Club', 'One NZ']
    assert values('final-restored-list.xml', 'profile_item_summary') == ['未启用', '未启用', '已启用', '未启用']
    eid = re.sub(r'\D', '', values('final-eid.xml', 'tv_eid')[0])
    first_eid = re.sub(r'\D', '', values('manage-eid.xml', 'tv_eid')[0])
    assert len(eid) == 32 and eid == first_eid
    assert any(
        n.get('checked') == 'true'
        for n in ET.parse(base / 'club-restored.xml').iter('node')
        if n.get('resource-id', '').endswith('profile_manage_switch_button')
    )
    assert values('rename-original.xml', 'dialog_input_area') == ['RedteaGO']

release_apk = out / 'Xiaomi-eSIM-Compat-v0.4.0.apk'
shutil.copy2(apk, release_apk)
release_tester_apk = out / 'Xiaomi-eSIM-Tester-v0.4.0.apk'
shutil.copy2(tester_apk, release_tester_apk)

source = out / 'XiaomiEsimLPA-HyperOS4-source-v0.4.0.zip'
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(base / 'build_module.py', 'build_module.py')
    if (base / 'README.md').exists():
        z.write(base / 'README.md', 'README.md')
        z.write(out / '小米eSIM模块-使用说明.md', 'USER_GUIDE_CN.md')
    else:
        z.write(out / '小米eSIM模块-使用说明.md', 'README.md')
    if (base / 'LICENSE').exists():
        z.write(base / 'LICENSE', 'LICENSE')
    for root, prefix in [(base / 'probe', 'probe'), (base / 'prebuilt', 'prebuilt')]:
        for p in sorted(root.rglob('*')):
            rel = p.relative_to(root)
            if not p.is_file() or any(s in {'build', '.gradle', '.git', '.idea'} for s in rel.parts):
                continue
            if p.suffix in {'.jks', '.keystore'} or p.name == 'local.properties':
                continue
            z.write(p, f'{prefix}/{rel.as_posix()}')

with zipfile.ZipFile(source) as z:
    assert z.testzip() is None
    assert not any('OfficialEstk' in n or 'upstream' in n for n in z.namelist())

report = {
    'version': '0.4.0',
    'date': '2026-11-04',
    'device': 'bixi',
    'build': 'OS4.0.0.11.XOHCNXM',
    'androidApi': 37,
    'lpaVersion': '4.1.0.1',
    'lpaVersionCode': 4101,
    'lpaSource': 'Xiaomi 17 Pro Max (popsicle) OS4.0.5.0.XPBCNXM China ROM',
    'lpaSha256': original_sha,
    'finalMode': 'on',
    'finalProfile': 'Club',
    'simState': 'LOADED,LOADED',
    'framework': {
        'ordinaryAppFeature': True,
        'ordinaryAppEuiccManagerEnabled': True,
        'defaultCardId': 1,
        'lpaUriBridge': True,
        'oneClickCarrierAuthDialog': True,
        'privilegedApiRealEidPresent': True,
        'ordinaryAppEidPermissionEnforced': True,
    },
    'management': {
        'profilesRead': 4,
        'eidDialogMatchesLiveCardAndSystemApi': True,
        'eidQrPresent': True,
        'switchClubToRedteaGoAndRestore': 'passed',
        'disableAndReenableRedteaGo': 'passed',
        'renameAndRestore': 'passed',
        'discovery': 'completed; Event list is empty (origin 2900, code 1001)',
        'add': 'scanner/manual entry inspected; invalid input rejected; mock one-click auth dialog verified',
        'deleteAndReset': 'confirmation/countdown inspected and cancelled; no profiles deleted',
        'advancedSettings': 'SM-DS viewed; SM-DP+ editor inspected and cancelled',
        'lpaPortCount': 1,
        'lpaPort0Active': True,
    },
    'systemSwitch': {
        'off': {'logical': 0, 'sim2Enabled': False, 'state': 'LOADED,NOT_READY'},
        'on': {'logical': 1, 'sim2Enabled': True, 'state': 'LOADED,LOADED'},
        'offPersistsAfterReboot': True,
    },
    'wholeModuleOffAndReboot': {
        'feature': False,
        'managerEnabled': False,
        'secureOriginalRestored': True,
        'globalOriginalRestored': True,
        'testerAppRemoved': True,
        'lpaBridgeDisabled': True,
        'state': 'LOADED,LOADED',
    },
    'wholeModuleOnAgain': {
        'feature': True,
        'managerEnabled': True,
        'realEidPresent': True,
        'fourProfilesPreserved': True,
        'clubEnabled': True,
    },
    'packaging': {
        'zipCrc': 'passed',
        'xmlParse': 'passed',
        'shellSyntaxOnDevice': 'passed',
        'installedApkMatchesModule': True,
        'originalLpaUnmodified': True,
        'upstreamCodeRemoved': True,
        'freshInstallDefault': 'off',
    },
    'screenTimeoutRestoredMs': 120000,
    'notTested': [
        'real profile download/install',
        'actual deletion/reset',
        'QR decoding of a valid activation code',
        'SM-DP+ address write',
        'ordinary SIM replacement (deferred at user request)',
        'other devices/firmware',
    ],
}
(out / '验证结果.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
files = [release_apk, release_tester_apk, module, source, out / '小米eSIM模块-使用说明.md', out / '验证结果.json']
(out / 'SHA256SUMS.txt').write_text(''.join(f'{sha(p.read_bytes())}  {p.name}\n' for p in files), encoding='utf-8')
print('Release validation passed; upstream removed; source excludes build caches and signing keys.')
for p in files[:4]:
    print(p.name, p.stat().st_size)
