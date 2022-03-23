# Bootiful Kubernetes Operators 


Make sure youre in the `default` namespace of a Kubernetes cluster. Not sure fi this matters but I am, so it might help.

Then, apply the config in the `k8s` directory:

```shell 
kubectl apply -f k8s/crd.yaml
``` 

then start the application:  

```shell
mvn clean spring-boot:run
``` 

Then deploy an isntance of the new CRD: 

```shell 
Kubect apply -f k8s/
```


if you want to get a native binary that you can run with very low memory, do: 


```shell 
mvn -Pnative clean package
```

if you want to get a Docker image containing the native binary, do: 


```shell 
mvn clean package spring-boot:build-image 
```

