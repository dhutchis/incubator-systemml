#!/usr/bin/env bash
set -o errexit
set -o pipefail
set -o nounset
# set -o xtrace
#Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename clogn n rho k alpha t [<binary | text>] [clogn_reduce_naive] [clogn_reduce_advanced]
#ex:    $0 data MR times.csv 18750 64 0.4 1 62.5 1875 binary 18000 18700
# export SPARK_HOME=/usr/iop/4.3.0.0-0000/spark2/

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
rho=0.4
c=$(echo "4.0 / ${rho} / ${rho}" | bc -l | stripDecimalZeros)
#c_naive=$(echo "5.0 / ${rho} / ${rho}" | bc -l | stripDecimalZeros)
# # of observations is alpha*n^(2/3)
alpha=$(echo "3.0 / ${rho} / ${rho}" | bc -l | stripDecimalZeros)

for n in 20000; do
  clogn=${alpha} #$(echo "${alpha} * ${n}) / l(2)" | bc -l | xargs printf "%.0f")
  #$(echo "${c} * l(${n}) / l(2)" | bc -l | xargs printf "%.0f")
  t=$(echo "${rho} / 1.2 " | bc -l ) #* ${c} * l(${n}) / l(2) | xargs printf "%.0f"
  clogn_reduce_naive=$(echo "${c} * l(${n}) / l(2)" | bc -l | xargs printf "%.0f")
  #clogn_reduce_advanced="$clogn"

  for rep in {1..5}; do
    CMD="./doExperiment.sh data SPARK times.csv ${clogn} ${n} ${rho} ${k} ${alpha} ${t} ${format} ${clogn_reduce_naive}" #${clogn_reduce_advanced}
    echo ${CMD}
    ${CMD}
  done

done
