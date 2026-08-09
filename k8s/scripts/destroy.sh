#!/bin/bash

echo "Destroying Bhukkad Kubernetes deployment..."
kubectl delete -k k8s/ --ignore-not-found=true
kubectl delete namespace bhukkad --ignore-not-found=true
echo "Done!"