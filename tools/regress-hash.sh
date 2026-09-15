#!/usr/bin/env bash
# Engine-level regression: translate the bench corpora with both GEMM paths of
# the same smoke binary and require (1) SMMLA == ruy bytes, (2) the canonical
# hash for this platform. Exact-integer GEMM means any kernel bug changes the
# hash -- this is the correctness gate for the whole engine, no COMET needed.
#   regress-hash.sh <smoke> <enzh-config.yml> <eng.txt> [<jaen-config.yml> <jpn.txt>]
# Env: EXPECT_ENZH / EXPECT_PIVOT override the built-in table; PLATFORM=host|host-ruy|device
# Current source uses compiled rules selected by ssplit-language. Set SSPLIT_PREFIX
# to an external legacy/custom table when investigating historical behavior.
# An explicitly empty SSPLIT_PREFIX disables abbreviation rules. Pivot reuses
# the source sentence annotations between translation legs.
# The pivot hash is only stable on an engine that carries patch 0012 (requests
# ordered by id, not heap address); without it the ja->zh output differs run to
# run even in blocking mode, so on such an engine check en->zh only.
set -euo pipefail
smoke=${1:?smoke}; enzh=${2:?enzh config}; eng=${3:?eng.txt}
jaen=${4:-}; jpn=${5:-}
platform=${PLATFORM:-host}

# Current scanner baseline: Apple Accelerate float GEMM, batch 1024, 200 lines.
# Other platforms require measurement and explicit EXPECT_* values;
# old sentence-splitter hashes must not be reused.
n=$(wc -l < "$eng" | tr -d ' ')
case "$platform/$n" in
  host/200) can_enzh=16537889a77b25db; can_pivot=efda28adbb22939d ;;
  host/*|host-ruy/*|device/*) can_enzh=; can_pivot= ;;
  *) echo "unknown PLATFORM=$platform"; exit 2 ;;
esac
expect_enzh=${EXPECT_ENZH:-$can_enzh}; expect_pivot=${EXPECT_PIVOT:-$can_pivot}
if [ -z "$expect_enzh" ] || { [ -n "$jaen" ] && [ -z "$expect_pivot" ]; }; then
  echo "no canonical hash for $platform with a $n-line corpus; pass EXPECT_ENZH / EXPECT_PIVOT"; exit 2
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Replace any existing top-level splitter settings instead of appending duplicate YAML keys.
prepare_config() {
  local input=$1 output=$2 language=$3
  sed '/^ssplit-language:/d; /^ssplit-builtin:/d; /^ssplit-prefix-file:/d' "$input" > "$output"
  printf '\nssplit-language: %s\n' "$language" >> "$output"
  if [ "$language" = en ] && [ "${SSPLIT_PREFIX+x}" = x ]; then
    printf 'ssplit-builtin: false\n' >> "$output"
    if [ -n "$SSPLIT_PREFIX" ]; then
      test -f "$SSPLIT_PREFIX" || { echo "missing prefix file: $SSPLIT_PREFIX"; exit 2; }
      # JSON strings are valid YAML; preserve spaces, quotes and special characters.
      python3 -c 'import json, sys; print("ssplit-prefix-file: " + json.dumps(sys.argv[1]))' "$SSPLIT_PREFIX" >> "$output"
    fi
  else
    printf 'ssplit-builtin: true\n' >> "$output"
  fi
}
prepare_config "$enzh" "$tmp/enzh.yml" en
enzh=$tmp/enzh.yml
if [ -n "$jaen" ]; then
  prepare_config "$jaen" "$tmp/jaen.yml" ja
  jaen=$tmp/jaen.yml
fi

run() { # $1 env-prefix  $2 dump-prefix  $3.. configs
  local envp=$1 dump=$2; shift 2
  env $envp "$smoke" --bench --dump-prefix "$tmp/$dump" "$@" 2>&1 >/dev/null | sed -n 's/.*pass0_hash=//p'
}
status=0
check() { # name hash_smmla hash_ruy expected dumpA dumpB
  local name=$1 hs=$2 hr=$3 exp=$4
  if [ "$hs" != "$hr" ]; then echo "FAIL $name: SMMLA $hs != ruy $hr"; status=1; fi
  if ! cmp -s "$5" "$6"; then echo "FAIL $name: dumps differ"; status=1; fi
  if [ "$hs" != "$exp" ]; then echo "FAIL $name: hash $hs != canonical $exp"; status=1; fi
  if [ "$hs" = "$hr" ] && [ "$hs" = "$exp" ] && cmp -s "$5" "$6"; then
    echo "OK   $name: $hs (SMMLA == ruy == canonical)"
  fi
  return 0
}

hs=$(run "" enzh-smmla "$enzh" < "$eng")
hr=$(run "BERGAMOT_NO_I8MM=1" enzh-ruy "$enzh" < "$eng")
check enzh "$hs" "$hr" "$expect_enzh" "$tmp/enzh-smmla.pass0.txt" "$tmp/enzh-ruy.pass0.txt"

if [ -n "$jaen" ]; then
  hs=$(run "" pivot-smmla "$jaen" "$enzh" < "$jpn")
  hr=$(run "BERGAMOT_NO_I8MM=1" pivot-ruy "$jaen" "$enzh" < "$jpn")
  check pivot "$hs" "$hr" "$expect_pivot" "$tmp/pivot-smmla.pass0.txt" "$tmp/pivot-ruy.pass0.txt"
fi
exit $status
