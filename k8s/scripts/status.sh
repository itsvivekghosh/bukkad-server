#!/bin/bash

echo ""
echo "================================================"
echo "  Bhukkad Kubernetes Status"
echo "================================================"
echo ""

echo "--- Namespace ---"
kubectl get namespace bhukkad 2>/dev/null || echo "Namespace not found"

echo ""
echo "--- Pods ---"
kubectl get pods -n bhukkad -o wide 2>/dev/null

echo ""
echo "--- Services ---"
kubectl get svc -n bhukkad 2>/dev/null

echo ""
echo "--- Deployments ---"
kubectl get deployments -n bhukkad 2>/dev/null

echo ""
echo "--- HPA ---"
kubectl get hpa -n bhukkad 2>/dev/null

echo ""
echo "--- Ingress ---"
kubectl get ingress -n bhukkad 2>/dev/null

echo ""
echo "--- Events (last 10) ---"
kubectl get events -n bhukkad --sort-by='.lastTimestamp' 2>/dev/null | tail -10

echo ""
echo "================================================"