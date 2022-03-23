package com.example.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

@Version("v1alpha1")
@Group("foocontroller.k8s.io")
@Plural("foos")
public class Foo extends CustomResource<FooSpec, FooStatus> implements Namespaced {

	public Foo(FooSpec spec, ObjectMeta objectMeta, FooStatus status) {
		this.setSpec(spec);
		this.setMetadata(objectMeta);
		this.setStatus(status);
	}

	public Foo() {
	}

}
