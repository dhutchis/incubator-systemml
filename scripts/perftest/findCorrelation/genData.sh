#!/usr/bin/env bash

printUsageExit()
{
  cat <<EOF
Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename A_filename ij_filename n clogn rho
ex:    $0 data SPARK times.csv A8 ij8 64 1125 0.4
EOF
  exit 1
}

for a in "$1" "$2" "$3" "$4" "$5" "$6" "$7"
do
  if [ "$a" == "" ]; then
      printUsageExit;
  fi
done
if [ "$2" == "SPARK" ]; then CMD="./sparkDML.sh "; DASH="-"; elif [ "$2" == "MR" ]; then CMD="hadoop jar SystemML.jar " ; else CMD="echo " ; fi

fDatagen="FindCorrelationDatagen.dml"
fTimes="$3"
fA="$4" #mboehm/spoof/w_${num_rows}
fij="$5"
n="$6"
clogn="$7"
rho="$8"

tstart=$SECONDS
${CMD} jar SystemML.jar -f ${fDatagen} -nvargs A=${fA} ij=${fij} n=${n} clogn=${clogn} rho=${rho}
echo "datagen,${clogn},${n},$(($SECONDS - $tstart - 3))" >> ${fTimes}
