#!/bin/bash

#POOLSIZE=$1
POOLNAME=pool-$(date +%Y%m%d%H%M%S)

prun -v -1 -np $POOLSIZE bin/java-run -Dibis.pool.name=$POOLNAME -Dibis.pool.size=$POOLSIZE -Dibis.server.address=fs0:4321 rubiks.ipl.Rubiks $@

wait
