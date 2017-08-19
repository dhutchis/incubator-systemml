#!/usr/bin/env bash
set -o errexit
set -o pipefail
set -o nounset
# set -o xtrace

printUsageExit()
{
  cat <<EOF
Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename clogn n rho k alpha t [clogn_reduce_naive] [clogn_reduce_advanced]
ex:    $0 data MR times.csv 18750 64 0.4 1 62.5 1875 18000 18700
EOF
  exit 1
}

for a in "${1:-}" "${2:-}" "${3:-}" "${4:-}" "${5:-}" "${6:-}" "${7:-}" "${8:-}" "${9:-}"
do
  if [ "${a}" = "" ]; then
      printUsageExit;
  fi
done
if [ "$2" = "SPARK" ]; then CMD="./sparkDML.sh "; DASH="-"; elif [ "$2" = "MR" ]; then CMD="yarn jar SystemML.jar " ; else CMD="echo " ; fi

fDatagen="FindCorrelationDatagen.dml"
fNaive="FindCorrelationNaive.dml"
fAdv="FindCorrelationAdvanced.dml"
fTimes="$3"
fTimesFail="FAIL_$3"
clogn="$4"
n="$5"
rho="$6"
k="$7"
alpha="$8"
t="$9"
clogn_reduce_naive="${10:-}"
clogn_reduce_advanced="${11:-}"

#hdfs dfs -ls /user/biuser/data
#hdfs dfs -ls /user/biuser/data/ij_18750_64
#cmp <(hdfs dfs -ls /user/biuser/data/ij_18750_64) <(hdfs dfs -ls /user/biuser/data/O_18750_64}
fA="$1/A_${clogn}_${n}"
fij="$1/ij_${clogn}_${n}"
fO1="$1/naive_${clogn}_${n}"
fO2="$1/advanced_${clogn}_${n}"

if ! hdfs dfs -test -f "${fA}" || ! hdfs dfs -test -f "${fA}.mtd"; then
tstart=$SECONDS
${CMD} jar SystemML.jar -f ${fDatagen} -nvargs A=${fA} ij=${fij} n=${n} clogn=${clogn} rho=${rho}
echo "datagen,${clogn},${n},${rho},$(($SECONDS - $tstart - 3))" >> ${fTimes}
fi

tstart=$SECONDS
if [ "$clogn_reduce_naive" = "" ]; then
  ${CMD} jar SystemML.jar -f ${fNaive} -nvargs A=${fA} O=${fO1}
else
  ${CMD} jar SystemML.jar -f ${fNaive} -nvargs A=${fA} O=${fO1} clogn_reduce=${clogn_reduce_naive}
fi
tend=$SECONDS
#echo "cmp <(hdfs dfs -cat ${fij}) <(hdfs dfs -cat ${fO1})"
#echo $?
if cmp --silent <(hdfs dfs -cat "${fij}") <(hdfs dfs -cat "${fO1}"); then
  echo "naive,${clogn},${n},${rho},${clogn_reduce_naive},$(($tend - $tstart - 3))" >> ${fTimes}
else
  echo "FAIL: naive,${clogn},${n},${rho},${clogn_reduce_naive},$(($tend - $tstart - 3))"
  echo "datagen: "
  hdfs dfs -cat "${fij}"
  echo "naive: "
  hdfs dfs -cat "${fO1}"
  echo "naive,${clogn},${n},${rho},${clogn_reduce_naive},$(($tend - $tstart - 3))" >> ${fTimesFail}
fi

tstart=$SECONDS
if [ "$clogn_reduce_advanced" = "" ]; then
  ${CMD} jar SystemML.jar -f ${fAdv} -nvargs A=${fA} O=${fO2} k=${k} alpha=${alpha} t=${t}
else
  ${CMD} jar SystemML.jar -f ${fAdv} -nvargs A=${fA} O=${fO2} k=${k} alpha=${alpha} t=${t} clogn_reduce=${clogn_reduce_advanced}
fi
tend=$SECONDS
if cmp --silent <(hdfs dfs -cat "${fij}") <(hdfs dfs -cat "${fO2}"); then
  echo "advanced,${clogn},${n},${rho},${clogn_reduce_advanced},$(($tend - $tstart - 3))" >> ${fTimes}
else
  echo "FAIL: advanced,${clogn},${n},${rho},${clogn_reduce_advanced},${k},${alpha},${t},$(($tend - $tstart - 3))"
  echo "datagen: "
  hdfs dfs -cat "${fij}"
  echo "advanced: "
  hdfs dfs -cat "${fO2}"
  echo "advanced,${clogn},${n},${rho},${clogn_reduce_advanced},${k},${alpha},${t},$(($tend - $tstart - 3))" >> ${fTimesFail}
fi

