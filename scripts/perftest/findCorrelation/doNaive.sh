#!/usr/bin/env bash

printUsageExit()
{
  cat <<EOF
Usage: $0 <hdfsDataDir> <MR | SPARK | ECHO> times_filename A_filename ij_filename O_filename [clogn_reduce]
ex:    $0 data SPARK times.csv A8 ij8 ij_naive8 1000
EOF
  exit 1
}

for a in "$1" "$2" "$3" "$4" "$5" "$6"
do
  if [ "$a" == "" ]; then
      printUsageExit;
  fi
done
if [ "$2" == "SPARK" ]; then CMD="./sparkDML.sh "; DASH="-"; elif [ "$2" == "MR" ]; then CMD="hadoop jar SystemML.jar " ; else CMD="echo " ; fi

fDatagen="FindCorrelationNaive.dml"
fTimes="$3"
fA="$4"
fij="$5"
fO="$6"
clogn="$7"

tstart=$SECONDS
if [ "$7" == "" ]; then
  ${CMD} jar SystemML.jar -f ${fDatagen} -nvargs A=${fA} ij=${fij} O=${fO}
else
  ${CMD} jar SystemML.jar -f ${fDatagen} -nvargs A=${fA} ij=${fij} O=${fO} clogn_reduce=${clogn}
fi
echo "naive,${clogn},$(($SECONDS - $tstart - 3))" >> ${fTimes}

# file comparison
# linux: cmp or diff
#cmp --silent $old $new || echo "files are different"
# windows: fc
#https://www.howtogeek.com/206123/how-to-use-fc-file-compare-from-the-windows-command-prompt/



