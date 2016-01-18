#!/bin/bash
for pid in `ps ww | grep Rubiks | sed 's/ //g' | cut -f1 -d"s"`
do
	kill $pid
done
