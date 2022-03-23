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

