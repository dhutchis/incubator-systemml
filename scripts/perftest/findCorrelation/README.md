# Find Correlated Variables Experiment


#### Scripts
* `./genData.sh <hdfsDataDir> <MR | SPARK | ECHO> times_filename A_filename ij_filename n clogn`
* `./runNaive.sh times_filename A_filename O_filename [clogn_reduce]`
* `./runAdvanced.sh times_filename A_filename O_filename k alpha t [clogn_reduce]`

#### DML Files
* `FindCorrelationDatagen.dml`
* `FindCorrelationNaive.dml`
* `FindCorrelationAdvanced.dml`

