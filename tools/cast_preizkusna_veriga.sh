#!/bin/sh
# Izmisljena veriga Cast za tests/CastPravilaTest.kt (25. 10. 2026): koren -> vmesno -> naprava, potrdilo TLS in podpis
# naprave nad (nonce || TLS) s SHA-256 - tako, kot podpise pravi zvocnik (izmerjeno na JBL BAR 300). Izpise samo javne dele
# (base64 DER, podpis); zasebni kljuci ostanejo v zacasni mapi in ne gredo v repozitorij.
#   sh tools/cast_preizkusna_veriga.sh
set -eu
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
cd "$D"
cat > ca.cnf <<'EOF'
[ v3_ca ]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
[ v3_ica ]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
[ v3_dev ]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
[ v3_ca_podpis ]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, digitalSignature, keyCertSign
EOF
openssl req -x509 -newkey rsa:2048 -nodes -keyout root.key -out root.pem -days 7300 -subj "/O=Safeer preizkus/CN=Preizkusni koren Cast" \
  -extensions v3_ca -config ca.cnf -set_serial 1 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout ica.key -out ica.csr -subj "/O=Safeer preizkus/CN=Preizkusno vmesno Cast" 2>/dev/null
openssl x509 -req -in ica.csr -CA root.pem -CAkey root.key -set_serial 2 -days 7000 -extfile ca.cnf -extensions v3_ica -out ica.pem 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout dev.key -out dev.csr -subj "/CN=Preizkusna naprava" 2>/dev/null
openssl x509 -req -in dev.csr -CA ica.pem -CAkey ica.key -set_serial 3 -days 6900 -extfile ca.cnf -extensions v3_dev -out dev.pem 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout ca2.key -out ca2.csr -subj "/O=Safeer preizkus/CN=Preizkusni CA, ki podpisuje" 2>/dev/null
openssl x509 -req -in ca2.csr -CA root.pem -CAkey root.key -set_serial 4 -days 6900 -extfile ca.cnf -extensions v3_ca_podpis -out ca2.pem 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -keyout tls.key -out tls.pem -days 2 -subj "/CN=preizkusni-tls" 2>/dev/null
for c in root ica dev tls ca2; do openssl x509 -in $c.pem -outform der -out $c.der; done
printf '0123456789abcdef' > nonce.bin
cat nonce.bin tls.der > vhod.bin
openssl dgst -sha256 -sign dev.key -out podpis.bin vhod.bin
openssl dgst -sha256 -sign dev.key -out podpis_brez_nonce.bin tls.der
openssl dgst -sha256 -sign ca2.key -out podpis_ca2.bin vhod.bin
b() { printf 'private const val %s = "%s"\n' "$1" "$(base64 -w0 "$2")"; }
b PREIZKUS_KOREN root.der
b PREIZKUS_VMESNO ica.der
b PREIZKUS_NAPRAVA dev.der
b PREIZKUS_TLS tls.der
b PREIZKUS_NONCE nonce.bin
b PREIZKUS_PODPIS podpis.bin
b PREIZKUS_PODPIS_BREZ_NONCE podpis_brez_nonce.bin
b PREIZKUS_CA_PODPISNIK ca2.der
b PREIZKUS_PODPIS_CA podpis_ca2.bin
