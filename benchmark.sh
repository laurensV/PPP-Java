#!/bin/bash
for config in "--size 3 --twists 11" "--size 3 --twists 12" "--size 4 --twists 11"; do
	echo $config >> results
	echo seq >> results
	for i in {1..3}; do
		./run-sequential.sh $config 2>&1 | grep milliseconds | cut -f4 -d" " >> results
	done
	for i in 1 2 4 8 12 16; do
		echo par $i >> results
		for j in {1..3}; do
			POOLSIZE=$i ./run-das.sh $config 2>&1 | grep milliseconds | cut -f4 -d" " >> results
		done
	done
done
