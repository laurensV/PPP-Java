#!/bin/bash

POOLSIZE=4
POOLNAME=pool-$(date +%Y%m%d%H%M%S)-$(date +%N)

for (( i=0; i < $POOLSIZE; i++ )); do
	bin/java-run -Dibis.pool.name=$POOLNAME -Dibis.pool.size=$POOLSIZE -Dibis.server.address=localhost:4321 rubiks.ipl.Rubiks $@ &
done

wait
