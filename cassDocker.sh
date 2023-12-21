#!/usr/bin/bash

docker pull cassandra:latest
docker network create -d bridge dodex-net

docker run --name dodex_cassandra -d --network dodex-net -p 127.0.0.1:9042:9042 cassandra:latest
