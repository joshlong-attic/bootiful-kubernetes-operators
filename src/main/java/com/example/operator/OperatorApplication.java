package com.example.operator;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.model.Scope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.Bean;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * This example was lifted and adapted
 * <a href="https://itnext.io/writing-kubernetes-sample-controller-in-java-c8edc38f348f">
 * from this blog</a>. Thank you, Rohan Kumar, for this excellent example. It has been
 * changed to use Spring Boot and to work with Spring Native.
 *
 * @author Rohan Kumar
 * @author Josh Long
 */
@Slf4j
@SpringBootApplication
public class OperatorApplication {

	public static void main(String[] args) throws Exception {
		SpringApplication.run(OperatorApplication.class, args);
	}

	@Bean
	ScheduledExecutorService scheduledExecutorService() {
		return Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
	}

	@Bean
	SharedInformerFactory sharedInformerFactory(KubernetesClient client) {
		return client.informers();
	}

	@Bean
	MixedOperation<Foo, FooList, Resource<Foo>> fooClient(KubernetesClient client) {
		return client.resources(Foo.class, FooList.class);
	}

	@Bean
	SharedIndexInformer<Deployment> deploymentSharedIndexInformer(SharedInformerFactory sharedInformerFactory) {
		return sharedInformerFactory.sharedIndexInformerFor(Deployment.class, 1000);
	}

	@Bean
	SharedIndexInformer<Foo> fooSharedIndexInformer(SharedInformerFactory sharedInformerFactory) {
		return sharedInformerFactory.sharedIndexInformerFor(Foo.class, 1000);
	}

	@Bean
	CustomResourceDefinitionContext foosCustomResourceDefinitionContext() {
		var value = Scope.NAMESPACED.value();
		var plural = Foo.class.getSimpleName().toLowerCase(Locale.ROOT) + "s";
		return new CustomResourceDefinitionContext.Builder() //
				.withVersion("v1alpha1") //
				.withScope(value)//
				.withGroup("foocontroller.k8s.io") //
				.withPlural(plural)//
				.build();
	}

	@Bean(destroyMethod = "close")
	KubernetesClient kubernetesClient() {
		return new DefaultKubernetesClient();
	}

	@Bean
	FooController fooController(SharedInformerFactory sharedInformerFactory, KubernetesClient client,
			MixedOperation<Foo, FooList, Resource<Foo>> fooClient,
			SharedIndexInformer<Deployment> deploymentSharedIndexInformer,
			SharedIndexInformer<Foo> fooSharedIndexInformer) {
		return new FooController(client, fooClient, deploymentSharedIndexInformer, fooSharedIndexInformer,
				client.getNamespace(), sharedInformerFactory);
	}

}
