#!/usr/bin/env bash
set -o errexit
set -o pipefail
set -o nounset
# set -o xtrace
#Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename clogn n rho k alpha t [<binary | text>] [clogn_reduce_naive] [clogn_reduce_advanced]
#ex:    $0 data MR times.csv 18750 64 0.4 1 62.5 1875 binary 18000 18700
#export SPARK_HOME=/usr/iop/4.3.0.0-0000/spark2/

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
alpha=$(echo "4.25 / ${rho} / ${rho}" | bc -l | stripDecimalZeros)

for n in 1183 1419 1703 2044 2453 2944 3533 4239 5087 6105 7326 8791 10550 12660 15192 18230 21876 26251 31502 37802 45363 54435 65323 78387 94065 112878 135454 162544 195053 234064 280877 337053 404463 485356 582428 698913 838696 1006435 1207722 1449267 1739120 2086944 2504333 3005200 3606240 4327489 5192986 6231584 7477901 8973481 10768177 12921813 15506175 18607411 22328893; do
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
