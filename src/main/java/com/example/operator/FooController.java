package com.example.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This demonstrates how to build a controller that manages creations and updates to a
 * CRD, {@link Foo}.
 *
 * TODO: figure out what a reasonable behavior for deletions would be.
 *
 * @author Rohan Kumar
 * @author Josh Long
 */
@Slf4j
class FooController implements ApplicationRunner, InitializingBean {

	private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(1024);

	private final SharedIndexInformer<Foo> fooInformer;

	private final SharedIndexInformer<Deployment> deploymentInformer;

	private final Lister<Foo> fooLister;

	private final KubernetesClient kubernetesClient;

	private final MixedOperation<Foo, FooList, Resource<Foo>> fooClient;

	private final SharedInformerFactory sharedInformerFactory;

	FooController(KubernetesClient kubernetesClient, MixedOperation<Foo, FooList, Resource<Foo>> fooClient,
			SharedIndexInformer<Deployment> deploymentInformer, SharedIndexInformer<Foo> fooInformer, String namespace,
			SharedInformerFactory sharedInformerFactory) {
		this.kubernetesClient = kubernetesClient;
		this.fooClient = fooClient;
		this.sharedInformerFactory = sharedInformerFactory;
		this.fooInformer = fooInformer;
		this.deploymentInformer = deploymentInformer;
		this.fooLister = new Lister<>(this.fooInformer.getIndexer(), namespace);
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		this.sharedInformerFactory.startAllRegisteredInformers();
		this.sharedInformerFactory.addSharedInformerEventListener(ex -> log.error("error", ex));
		while (!this.deploymentInformer.hasSynced() || !this.fooInformer.hasSynced()) {
			log.debug("Waiting for informer caches to sync");
			Thread.sleep(1000);
		}
		log.debug("trying to fetch work from the work queue. work queue is empty? {} ", "" + this.queue.isEmpty());
		while (!Thread.currentThread().isInterrupted()) {
			try {
				this.processQueue();
			}
			catch (Throwable t) {
				log.error("there's been an exception in " + getClass().getName() + "#run()", t);
				Thread.currentThread().interrupt();
			}
		}
	}

	private void processQueue() throws InterruptedException {
		var key = (String) this.queue.take();
		Assert.hasText(key, "key can't be null");
		log.debug("Got {}", key);
		Assert.isTrue(StringUtils.hasText(key) && key.contains("/"),
				() -> String.format("invalid resource key: %s", key));
		var name = key.split("/")[1]; // format: namespace/name
		var foo = fooLister.get(name);
		Assert.notNull(foo, () -> String.format("Foo %s in work queue no longer exists!", foo));
		log.debug("trying to reconcile {}", foo);
		// Get the deployment with the name specified in Foo.spec
		var deploymentName = foo.getSpec().getDeploymentName();
		if (!StringUtils.hasText(deploymentName)) {
			log.warn("No Deployment name specified for Foo {}/{}", foo.getMetadata().getNamespace(),
					foo.getMetadata().getName());
		}
		log.debug("trying to get the Deployment...");
		var deployment = (Deployment) null;
		try {
			deployment = this.kubernetesClient//
					.apps()//
					.deployments()//
					.inNamespace(foo.getMetadata().getNamespace())//
					.withName(deploymentName)//
					.get();
		}
		catch (Throwable e) {
			log.warn("couldn't find a deployment with the deploymentName {}", deploymentName);
		}

		if (deployment == null) {
			log.debug("going to create a Deployment " + foo);
			var newDeployment = createNewDeployment(foo);
			log.debug("created the new Deployment using " + newDeployment.getFullResourceName());
			this.kubernetesClient.apps().deployments().inNamespace(foo.getMetadata().getNamespace())
					.create(newDeployment);
			log.debug("used the KubernetesClient to create a new Deployment");
			return;
		}

		if (!isControlledBy(deployment, foo)) {
			log.warn("Deployment {} is not controlled by Foo {}", deployment.getMetadata().getName(),
					foo.getMetadata().getName());
			return;
		}

		// make sure deployment has same # of replicas as Foo's spec has
		if (foo.getSpec().getReplicas() != deployment.getSpec().getReplicas()) {
			log.debug("updating the replica count for the deployment with name {}", deployment.getFullResourceName());
			deployment.getSpec().setReplicas(foo.getSpec().getReplicas());
			this.kubernetesClient //
					.apps() //
					.deployments() //
					.inNamespace(foo.getMetadata().getNamespace()) //
					.withName(deployment.getMetadata().getName()) //
					.replace(deployment);
		}

		var fooStatus = new FooStatus();
		fooStatus.setAvailableReplicas(foo.getSpec().getReplicas());

		var cloneFooSpec = new FooSpec();
		cloneFooSpec.setDeploymentName(foo.getSpec().getDeploymentName());
		cloneFooSpec.setReplicas(foo.getSpec().getReplicas());

		var cloneFoo = new Foo();
		cloneFoo.setSpec(cloneFooSpec);
		cloneFoo.setMetadata(foo.getMetadata());
		cloneFoo.setStatus(fooStatus);
		this.fooClient.inNamespace(foo.getMetadata().getNamespace()).withName(foo.getMetadata().getName())
				.replaceStatus(foo);
		log.debug("replaced the status for {} ", foo);
	}

	private void enqueueFoo(Foo foo) {
		var key = Cache.metaNamespaceKeyFunc(foo);
		if (StringUtils.hasText(key)) {
			log.debug("enqueuing Foo with key {}", key);
			this.queue.add(key);
		}
	}

	private void handleDeployment(Deployment deployment) {
		this.getControllerOf(deployment)//
				.ifPresent(or -> {
					if (!or.getKind().equalsIgnoreCase(Foo.class.getSimpleName()))
						return;
					log.debug("handleDeploymentObject({})", deployment.getMetadata().getName());
					Optional //
							.ofNullable(fooLister.get(or.getName())) //
							.ifPresentOrElse(this::enqueueFoo, () -> log.debug("couldn't find the foo for {}",
									deployment.getMetadata().getName()));//
				});
	}

	/**
	 * {@link this#createNewDeployment(Foo)} creates a new {@link Deployment} for a
	 * {@link Foo} resource. It also sets the appropriate {@link OwnerReference} on the
	 * resource so handleObject can discover the Foo resource that 'owns' it.
	 * @param foo {@link Foo} resource which will be owner of this Deployment
	 * @return Deployment object based on this Foo resource
	 */
	private Deployment createNewDeployment(Foo foo) {
		log.debug("creating the new Deployment object using the  " + DeploymentBuilder.class.getName());
		//@formatter:off
		return new DeploymentBuilder()
			.withNewMetadata()
				.withName(foo.getSpec().getDeploymentName())
				.withNamespace(foo.getMetadata().getNamespace())
				.withLabels(getDeploymentLabels(foo))
				.addNewOwnerReference()
					.withController(true)
					.withKind(foo.getKind())
					.withApiVersion(foo.getApiVersion())
					.withName(foo.getMetadata().getName())
					.withUid(foo.getMetadata().getUid())
				.endOwnerReference()
			.endMetadata()
			.withNewSpec()
				.withReplicas(foo.getSpec().getReplicas())
				.withNewSelector()
					.withMatchLabels(getDeploymentLabels(foo))
				.endSelector()
			.withNewTemplate()
				.withNewMetadata()
					.withLabels(getDeploymentLabels(foo))
				.endMetadata()
				.withNewSpec()
					.addNewContainer()
						.withName("nginx")
						.withImage("nginx:latest")
					.endContainer()
				.endSpec()
			.endTemplate()
		.endSpec()
		.build();
		//@formatter:on
	}

	private Map<String, String> getDeploymentLabels(Foo foo) {
		return Map.of( //
				"app", "nginx", //
				"controller", foo.getMetadata().getName() //
		);
	}

	private Optional<OwnerReference> getControllerOf(HasMetadata obj) {
		return obj.getMetadata().getOwnerReferences().stream().filter(OwnerReference::getController).findFirst();
	}

	private boolean isControlledBy(Deployment obj, Foo foo) {
		return getControllerOf(obj) //
				.map(or -> (or.getKind().equalsIgnoreCase(foo.getKind())
						&& or.getName().equalsIgnoreCase(foo.getMetadata().getName()))) //
				.orElse(false);
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		// Set up an event handler for when Foo resources change
		fooInformer.addEventHandler(new ResourceEventHandler<Foo>() {
			@Override
			public void onAdd(Foo foo) {
				log.debug("onAdd(Foo)");
				enqueueFoo(foo);
			}

			@Override
			public void onUpdate(Foo foo, Foo newFoo) {
				log.debug("onUpdate(Foo)");
				enqueueFoo(newFoo);
			}

			@Override
			public void onDelete(Foo foo, boolean b) {
			}
		});

		// Set up an event handler for when Deployment resources change. This
		// handler will lookup the owner of the given Deployment, and if it is
		// owned by a Foo resource will enqueue that Foo resource for
		// processing. This way, we don't need to implement custom logic for
		// handling Deployment resources. More info on this pattern:
		// https://github.com/kubernetes/community/blob/8cafef897a22026d42f5e5bb3f104febe7e29830/contributors/devel/controllers.md
		deploymentInformer.addEventHandler(new ResourceEventHandler<Deployment>() {
			@Override
			public void onAdd(Deployment deployment) {
				handleDeployment(deployment);
			}

			@Override
			public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
				// Periodic resyncd will send update events for all known Deployments.
				// Two different versions of the same Deployment will always have
				// different RVs.
				if (oldDeployment.getMetadata().getResourceVersion()
						.equals(newDeployment.getMetadata().getResourceVersion())) {
					return;
				}
				handleDeployment(newDeployment);
			}

			@Override
			public void onDelete(Deployment deployment, boolean b) {
				handleDeployment(deployment);
			}
		});
	}

}
