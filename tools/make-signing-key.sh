#!/data/data/com.termux/files/usr/bin/bash
# Make the app's ONE permanent signing key on the phone and give it to GitHub Actions as two secrets.
# Run once, in Termux, with gh logged in (gh auth status). MANTRA_MANIFEST android-app.md §3:
# the key never changes for the life of the app — a new key is a new app to Android.
#
#   pkg install python python-cryptography gh      (once)
#   bash tools/make-signing-key.sh
set -euo pipefail
REPO=markoboskoauroville/Song_simple_mixer
DIR="$HOME/.mixer-signing"
if [ -f "$DIR/mixer.p12" ]; then
  echo "A key already exists in $DIR — reusing it (never make a second one)."
else
  mkdir -p "$DIR" && chmod 700 "$DIR"
  python3 - "$DIR" <<'PY'
import datetime, secrets, sys, os
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12, BestAvailableEncryption
d = sys.argv[1]
pw = secrets.token_urlsafe(24)
key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Song simple mixer"),
                  x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Mantra Productions")])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now)
        .not_valid_after(now + datetime.timedelta(days=365 * 40)).sign(key, hashes.SHA256()))
open(os.path.join(d, "mixer.p12"), "wb").write(
    pkcs12.serialize_key_and_certificates(b"mixer", key, cert, None, BestAvailableEncryption(pw.encode())))
open(os.path.join(d, "password"), "w").write(pw)
print("certificate SHA-256:", cert.fingerprint(hashes.SHA256()).hex())
PY
  chmod 600 "$DIR"/*
fi
base64 -w0 "$DIR/mixer.p12" | gh secret set MIXER_KEYSTORE -R "$REPO"
gh secret set MIXER_KEYSTORE_PASSWORD -R "$REPO" < "$DIR/password"
gh secret list -R "$REPO"
echo "Done. Keep $DIR — it is the only copy outside GitHub. Then: gh workflow run build-apk.yml -R $REPO"
