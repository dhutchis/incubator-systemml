#!/usr/bin/env bash

printUsageExit()
{
  cat <<EOF
Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename clogn n rho
ex:    $0 data MR times.csv 1125 64 0.4
EOF
  exit 1
}

for a in "$1" "$2" "$3" "$4" "$5"
do
  if [ "$a" == "" ]; then
      printUsageExit;
  fi
done
if [ "$2" == "SPARK" ]; then CMD="./sparkDML.sh "; DASH="-"; elif [ "$2" == "MR" ]; then CMD="yarn jar SystemML.jar " ; else CMD="echo " ; fi

#hdfs dfs -ls /user/biuser/data
fDatagen="FindCorrelationDatagen.dml"
fTimes="$3"
clogn="$4"
n="$5"
rho="$6"

fA="$1/A_${n}_${clogn}"
fij="$1/ij_${n}_${clogn}"

tstart=$SECONDS
${CMD} jar SystemML.jar -f ${fDatagen} -nvargs A=${fA} ij=${fij} n=${n} clogn=${clogn} rho=${rho}
echo "datagen,${clogn},${n},$(($SECONDS - $tstart - 3))" >> ${fTimes}
