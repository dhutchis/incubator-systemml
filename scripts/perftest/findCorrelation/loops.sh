#!/usr/bin/env bash
set -o errexit
set -o pipefail
set -o nounset
# set -o xtrace
#Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename clogn n rho k alpha t [<binary | text>] [clogn_reduce_naive] [clogn_reduce_advanced]
#ex:    $0 data MR times.csv 18750 64 0.4 1 62.5 1875 binary 18000 18700

stringContain() { [ -z "${2##*$1*}" ] && [ -z "$1" -o -n "$2" ]; }
# 3125.5300000000 ==> 3125.53
# 3125.0000000000 ==> 3125
stripDecimalZeros() {
  a=${1:-$(</dev/stdin)};
  if stringContain "." "${a}"; then
    a=${a%%0*}
    # if last character is a '.', remove it
    n=${#a}
    n=$((n - 1))
    if [ "${a:${n}:${n}}" = "." ]; then
      a=${a:0:${n}}
    fi
  fi
  echo ${a}
}

format="binary"
k=1
rho=0.8
c=$(echo "60.0 / ${rho} / ${rho}" | bc -l | stripDecimalZeros)
alpha=$(echo "2.0 / ${rho}" | bc -l | stripDecimalZeros)

for n in 4096; do
  clogn=$(echo "${c} * l(${n}) / l(2)" | bc -l | xargs printf "%.0f")
  t=$(echo "${rho} / 4 * ${c} * l(${n}) / l(2)" | bc -l | xargs printf "%.0f")
  #clogn_reduce_naive="$clogn"
  #clogn_reduce_advanced="$clogn"

  for rep in {1..5}; do
    ./doExperiment.sh data MR times.csv ${clogn} ${n} ${rho} ${k} ${alpha} ${t} ${format}
  done

done
