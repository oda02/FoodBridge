"""Create a persistent local release key. Never prints or commits passwords."""
from pathlib import Path
import hashlib, json, os, secrets, subprocess

root = Path(__file__).resolve().parents[1]
private = root / 'private'
private.mkdir(exist_ok=True)
props = root / 'signing.properties'
key = private / 'foodbridge-release.p12'
if key.exists() or props.exists():
    raise SystemExit('Signing files already exist. Reuse them; never regenerate an update key.')
password = secrets.token_urlsafe(40)
env = dict(os.environ, FOODBRIDGE_SIGN_PASSWORD=password)
subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
    '-alias', 'foodbridge', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
    '-dname', 'CN=FoodBridge', '-storepass:env', 'FOODBRIDGE_SIGN_PASSWORD', '-keypass:env', 'FOODBRIDGE_SIGN_PASSWORD'], env=env, check=True)
props.write_text('storeFile=private/foodbridge-release.p12\nstorePassword='+password+'\nkeyAlias=foodbridge\nkeyPassword='+password+'\n', encoding='utf-8')
cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(key), '-alias', 'foodbridge', '-storepass:env', 'FOODBRIDGE_SIGN_PASSWORD'], env=env, check=True, capture_output=True).stdout
fingerprint = ':'.join(f'{b:02X}' for b in hashlib.sha256(cert).digest())
target = root / 'site/dist/.well-known/assetlinks.json'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps([{'relation':['delegate_permission/common.handle_all_urls'],'target':{'namespace':'android_app','package_name':'ru.kukakur.foodbridge','sha256_cert_fingerprints':[fingerprint]}}], indent=2)+'\n', encoding='utf-8')
(root / 'docs').mkdir(exist_ok=True)
(root / 'docs/release-certificate.txt').write_text('FoodBridge release signing certificate SHA-256\n'+fingerprint+'\n', encoding='utf-8')
print('Release key created in private/foodbridge-release.p12; password in ignored signing.properties. Back up both securely.')
print('Certificate SHA-256:', fingerprint)
