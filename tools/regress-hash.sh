#!/usr/bin/env bash
# Engine-level regression: translate the bench corpora with both GEMM paths of
# the same smoke binary and require (1) SMMLA == ruy bytes, (2) the canonical
# hash for this platform. Exact-integer GEMM means any kernel bug changes the
# hash -- this is the correctness gate for the whole engine, no COMET needed.
#   regress-hash.sh <smoke> <enzh-config.yml> <eng.txt> [<jaen-config.yml> <jpn.txt>]
# Env: EXPECT_ENZH / EXPECT_PIVOT override the built-in table; PLATFORM=host|host-ruy|device
# The canonical hashes are for a splitter that HAS the source-language
# nonbreaking-prefix table, which is what the AAR now ships by default. The
# script appends `ssplit-prefix-file: $SSPLIT_PREFIX` to its own copy of the enzh
# config; SSPLIT_PREFIX defaults to the en table vendored next to this script.
# Point it elsewhere when the tables live somewhere else (a device run needs the
# pushed path), or set it empty to reproduce a pre-prefix-table hash.
# The jaen config is left alone: ssplit-cpp has no Japanese table, and the second
# leg of a pivot re-uses the boundaries the first leg found instead of splitting
# again -- which is why the pivot hashes below did not move.
# The pivot hash is only stable on an engine that carries patch 0012 (requests
# ordered by id, not heap address); without it the ja->zh output differs run to
# run even in blocking mode, so on such an engine check en->zh only.
set -euo pipefail
smoke=${1:?smoke}; enzh=${2:?enzh config}; eng=${3:?eng.txt}
jaen=${4:-}; jpn=${5:-}
platform=${PLATFORM:-host}

# Canonical blocking hashes (FNV-1a over the corpus output) by platform, config
# and corpus size: host runs the mbw1024 CI config, devices run the mbw512
# default. The hash covers the whole corpus and batch composition changes the
# output of individual sentences, so each corpus size has its own table entry
# (bench set grew from FLORES lines 1-150 to 1-200 on 2026-09-04).
# Host table re-baselined 2026-09-05 for patch 0018: the small-GEMM kernel
# reproduces ruy's accumulation order, which differs from Accelerate's, so the
# Accelerate host build moves (host/150 pivot happens not to); every device runs
# ruy and is unchanged. Pre-0018 host values: host/150 1728c7c863926c5c/
# 58b6667dd43d6364, host/200 3b458f7f7fe6fd68/00602a479d7c106b.
# Host en->zh re-baselined 2026-09-06 for the nonbreaking-prefix table: it merges
# the fragments the bare regex cut at `Dr.`, `U.S.`, `No. 5` and the like (200
# lines: 226 sentences -> 212), so the corpus output changes. Pre-table en->zh
# values: host/150 0a46cfb6e339ff29, host/200 cec5ff3b1fc29f8e.
# Device en->zh re-baselined the same day on a Mi 12 (SMMLA == ruy, both corpus
# sizes; pivot unchanged). Pre-table device en->zh values: device/150
# 1742b57a069b1da7, device/200 f8a315e6571cc957.
n=$(wc -l < "$eng" | tr -d ' ')
case "$platform/$n" in
  host/150)   can_enzh=a548669c86d03fc1; can_pivot=58b6667dd43d6364 ;;
  host/200)   can_enzh=16537889a77b25db; can_pivot=e9d84f82b99250ee ;;
  device/150) can_enzh=a61c0d35a4d8f2e8; can_pivot=28028fc1ed7d0099 ;;
  device/200) can_enzh=88295d89303c20bd; can_pivot=fb3dda796b1186af ;;   # en->zh: Mi 12 2026-09-06 (prefix table); pivot: Mi 10 (865, ruy) 2026-09-04, Mi 14 SMMLA == ruy 2026-09-05
  # Host build with the float GEMM on ruy (-DUSE_APPLE_ACCELERATE=OFF -DUSE_RUY_SGEMM=ON):
  # attention float products accumulate in ruy order, so the hashes differ from
  # the Accelerate host table above. Baselined 2026-09-05 on the D0 tree; en->zh
  # re-baselined 2026-09-06 for the prefix table (pre-table: host-ruy/150
  # 54754317ebc21206, host-ruy/200 01802271f3d28be7), pivot unchanged.
  host-ruy/150) can_enzh=19a4e7f9052e5c18; can_pivot=c9bbc00386d027f4 ;;
  host-ruy/200) can_enzh=54fdd3c3cb2f9f4e; can_pivot=7dcaeab06551bf6d ;;
  host/*|host-ruy/*|device/*) can_enzh=; can_pivot= ;;
  *) echo "unknown PLATFORM=$platform"; exit 2 ;;
esac
expect_enzh=${EXPECT_ENZH:-$can_enzh}; expect_pivot=${EXPECT_PIVOT:-$can_pivot}
if [ -z "$expect_enzh" ] || { [ -n "$jaen" ] && [ -z "$expect_pivot" ]; }; then
  echo "no canonical hash for $platform with a $n-line corpus; pass EXPECT_ENZH / EXPECT_PIVOT"; exit 2
fi

tmp=$(mktemp -d)

# Hand the splitter its prefix table, unless the caller opted out with an empty
# SSPLIT_PREFIX. The engine reads the option out of the model config, so the
# only way in is a config that names it.
here=$(cd "$(dirname "$0")" && pwd)
ssplit_prefix=${SSPLIT_PREFIX-$here/../engine/3rd_party/ssplit-cpp/nonbreaking_prefixes/nonbreaking_prefix.en}
if [ -n "$ssplit_prefix" ]; then
  if [ ! -f "$ssplit_prefix" ]; then
    echo "no ssplit prefix file at $ssplit_prefix; set SSPLIT_PREFIX (empty to skip)"; rm -rf "$tmp"; exit 2
  fi
  cp "$enzh" "$tmp/enzh-with-prefixes.yml"
  echo "ssplit-prefix-file: $ssplit_prefix" >> "$tmp/enzh-with-prefixes.yml"
  enzh=$tmp/enzh-with-prefixes.yml
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
  [ $status -eq 0 ] && echo "OK   $name: $hs (SMMLA == ruy == canonical)"
}

hs=$(run "" enzh-smmla "$enzh" < "$eng")
hr=$(run "BERGAMOT_NO_I8MM=1" enzh-ruy "$enzh" < "$eng")
check enzh "$hs" "$hr" "$expect_enzh" "$tmp/enzh-smmla.pass0.txt" "$tmp/enzh-ruy.pass0.txt"

if [ -n "$jaen" ]; then
  hs=$(run "" pivot-smmla "$jaen" "$enzh" < "$jpn")
  hr=$(run "BERGAMOT_NO_I8MM=1" pivot-ruy "$jaen" "$enzh" < "$jpn")
  check pivot "$hs" "$hr" "$expect_pivot" "$tmp/pivot-smmla.pass0.txt" "$tmp/pivot-ruy.pass0.txt"
fi
rm -rf "$tmp"
exit $status
