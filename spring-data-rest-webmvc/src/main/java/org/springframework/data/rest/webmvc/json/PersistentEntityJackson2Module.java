/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc.json;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.repository.support.RepositoryInvoker;
import org.springframework.data.repository.support.RepositoryInvokerFactory;
import org.springframework.data.rest.core.UriToEntityConverter;
import org.springframework.data.rest.webmvc.PersistentEntityResource;
import org.springframework.data.rest.webmvc.mapping.Associations;
import org.springframework.data.rest.webmvc.mapping.LinkCollector;
import org.springframework.hateoas.UriTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.std.CollectionDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerBuilder;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.CollectionLikeType;

/**
 * Jackson 2 module to serialize and deserialize {@link PersistentEntityResource}s.
 *
 * @author Jon Brisbin
 * @author Oliver Gierke
 * @author Greg Turnquist
 * @author Anton Koscejev
 */
public class PersistentEntityJackson2Module extends SimpleModule {

	private static final long serialVersionUID = -7289265674870906323L;
	private static final Logger LOG = LoggerFactory.getLogger(PersistentEntityJackson2Module.class);
	private static final TypeDescriptor URI_DESCRIPTOR = TypeDescriptor.valueOf(URI.class);

	/**
	 * Creates a new {@link PersistentEntityJackson2Module} using the given parameters.
	 *
	 * @param associations must not be {@literal null}.
	 * @param entities     must not be {@literal null}.
	 * @param converter    must not be {@literal null}.
	 * @param collector    must not be {@literal null}.
	 */
	public PersistentEntityJackson2Module(Associations associations, PersistentEntities entities,
			UriToEntityConverter converter, LinkCollector collector, RepositoryInvokerFactory factory,
			NestedEntitySerializer serializer, LookupObjectSerializer lookupObjectSerializer) {

		super(new Version(2, 0, 0, null, "org.springframework.data.rest", "jackson-module"));

		Assert.notNull(associations, "AssociationLinks must not be null!");
		Assert.notNull(entities, "Repositories must not be null!");
		Assert.notNull(converter, "UriToEntityConverter must not be null!");
		Assert.notNull(collector, "LinkCollector must not be null!");

		addSerializer(new PersistentEntityResourceSerializer(collector));
		addSerializer(new TargetAwareSerializer(collector, associations));
		addSerializer(new ProjectionResourceContentSerializer());

		setSerializerModifier(
				new AssociationOmittingSerializerModifier(entities, associations, serializer, lookupObjectSerializer));
		setDeserializerModifier(
				new AssociationUriResolvingDeserializerModifier(entities, associations, converter, factory));
	}

	/**
	 * {@link BeanSerializerModifier} to drop the property descriptors for associations.
	 *
	 * @author Oliver Gierke
	 */
	@RequiredArgsConstructor
	static class AssociationOmittingSerializerModifier extends BeanSerializerModifier {

		private final @NonNull PersistentEntities entities;
		private final @NonNull Associations associations;
		private final @NonNull NestedEntitySerializer nestedEntitySerializer;
		private final @NonNull LookupObjectSerializer lookupObjectSerializer;

		/* 
		 * (non-Javadoc)
		 * @see com.fasterxml.jackson.databind.ser.BeanSerializerModifier#updateBuilder(com.fasterxml.jackson.databind.SerializationConfig, com.fasterxml.jackson.databind.BeanDescription, com.fasterxml.jackson.databind.ser.BeanSerializerBuilder)
		 */
		@Override
		public BeanSerializerBuilder updateBuilder(SerializationConfig config, BeanDescription beanDesc,
				BeanSerializerBuilder builder) {

			PersistentEntity<?, ?> entity = entities.getPersistentEntity(beanDesc.getBeanClass());

			if (entity == null) {
				return builder;
			}

			List<BeanPropertyWriter> result = new ArrayList<BeanPropertyWriter>();

			for (BeanPropertyWriter writer : builder.getProperties()) {

				// Skip exported associations
				PersistentProperty<?> persistentProperty = findProperty(writer.getName(), entity, beanDesc);

				if (persistentProperty == null) {
					result.add(writer);
					continue;
				}

				if (associations.isLookupType(persistentProperty)) {

					LOG.debug("Assigning lookup object serializer for {}.", persistentProperty);
					writer.assignSerializer(lookupObjectSerializer);
					result.add(writer);
					continue;
				}

				// Is there a default projection?

				if (associations.isLinkableAssociation(persistentProperty)) {
					continue;
				}

				// Skip ids unless explicitly configured to expose
				if (persistentProperty.isIdProperty() && !associations.isIdExposed(entity)) {
					continue;
				}

				if (persistentProperty.isVersionProperty()) {
					continue;
				}

				if (persistentProperty.isEntity()) {

					LOG.debug("Assigning nested entity serializer for {}.", persistentProperty);

					writer.assignSerializer(nestedEntitySerializer);
				}

				result.add(writer);
			}

			builder.setProperties(result);

			return builder;
		}

		/**
		 * Returns the {@link PersistentProperty} for the property with the given final name (the name that it will be
		 * rendered under eventually).
		 *
		 * @param finalName the output name the property will be rendered under.
		 * @param entity the {@link PersistentEntity} to find the property on.
		 * @param description the Jackson {@link BeanDescription}.
		 * @return
		 */
		private PersistentProperty<?> findProperty(String finalName, PersistentEntity<?, ?> entity,
				BeanDescription description) {

			for (BeanPropertyDefinition definition : description.findProperties()) {
				if (definition.getName().equals(finalName)) {
					return entity.getPersistentProperty(definition.getInternalName());
				}
			}

			return null;
		}
	}

	/**
	 * A {@link BeanDeserializerModifier} that registers a custom {@link UriStringDeserializer} for association properties
	 * of {@link PersistentEntity}s. This allows to submit URIs for those properties in request payloads, so that
	 * non-optional associations can be populated on resource creation.
	 *
	 * @author Oliver Gierke
	 */
	@RequiredArgsConstructor
	public static class AssociationUriResolvingDeserializerModifier extends BeanDeserializerModifier {

		private final @NonNull PersistentEntities entities;
		private final @NonNull Associations associationLinks;
		private final @NonNull UriToEntityConverter converter;
		private final @NonNull RepositoryInvokerFactory factory;

		/* 
		 * (non-Javadoc)
		 * @see com.fasterxml.jackson.databind.deser.BeanDeserializerModifier#updateBuilder(com.fasterxml.jackson.databind.DeserializationConfig, com.fasterxml.jackson.databind.BeanDescription, com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder)
		 */
		@Override
		public BeanDeserializerBuilder updateBuilder(DeserializationConfig config, BeanDescription beanDesc,
				BeanDeserializerBuilder builder) {

			Iterator<SettableBeanProperty> properties = builder.getProperties();
			PersistentEntity<?, ?> entity = entities.getPersistentEntity(beanDesc.getBeanClass());

			if (entity == null) {
				return builder;
			}

			while (properties.hasNext()) {

				SettableBeanProperty property = properties.next();
				PersistentProperty<?> persistentProperty = entity.getPersistentProperty(property.getName());

				if (associationLinks.isLookupType(persistentProperty)) {

					RepositoryInvokingDeserializer repositoryInvokingDeserializer = new RepositoryInvokingDeserializer(factory,
							persistentProperty);
					JsonDeserializer<?> deserializer = wrapIfCollection(persistentProperty, repositoryInvokingDeserializer,
							config);

					builder.addOrReplaceProperty(property.withValueDeserializer(deserializer), false);
					continue;
				}

				if (!associationLinks.isLinkableAssociation(persistentProperty)) {
					continue;
				}

				UriStringDeserializer uriStringDeserializer = new UriStringDeserializer(persistentProperty, converter);
				JsonDeserializer<?> deserializer = wrapIfCollection(persistentProperty, uriStringDeserializer, config);

				builder.addOrReplaceProperty(property.withValueDeserializer(deserializer), false);
			}

			return builder;
		}

		private static JsonDeserializer<?> wrapIfCollection(PersistentProperty<?> property,
				JsonDeserializer<Object> elementDeserializer, DeserializationConfig config) {

			if (!property.isCollectionLike()) {
				return elementDeserializer;
			}

			CollectionLikeType collectionType = config.getTypeFactory().constructCollectionLikeType(property.getType(),
					property.getActualType());
			CollectionValueInstantiator instantiator = new CollectionValueInstantiator(property);
			return new CollectionDeserializer(collectionType, elementDeserializer, null, instantiator);
		}
	}

	/**
	 * Custom {@link JsonDeserializer} to interpret {@link String} values as URIs and resolve them using a
	 * {@link UriToEntityConverter}.
	 *
	 * @author Oliver Gierke
	 * @author Valentin Rentschler
	 */
	static class UriStringDeserializer extends StdDeserializer<Object> {

		private static final long serialVersionUID = -2175900204153350125L;
		private static final String UNEXPECTED_VALUE = "Expected URI cause property %s points to the managed domain type!";

		private final PersistentProperty<?> property;
		private final UriToEntityConverter converter;

		/**
		 * Creates a new {@link UriStringDeserializer} for the given {@link PersistentProperty} using the given
		 * {@link UriToEntityConverter}.
		 *
		 * @param property must not be {@literal null}.
		 * @param converter must not be {@literal null}.
		 */
		public UriStringDeserializer(PersistentProperty<?> property, UriToEntityConverter converter) {

			super(property.getActualType());

			this.property = property;
			this.converter = converter;
		}

		/* 
		 * (non-Javadoc)
		 * @see com.fasterxml.jackson.databind.JsonDeserializer#deserialize(com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.DeserializationContext)
		 */
		@Override
		public Object deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {

			String source = jp.getValueAsString();

			if (!StringUtils.hasText(source)) {
				return null;
			}

			try {
				URI uri = new UriTemplate(source).expand();
				TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(property.getActualType());

				return converter.convert(uri, URI_DESCRIPTOR, typeDescriptor);
			} catch (IllegalArgumentException o_O) {
				throw ctxt.weirdStringException(source, URI.class, String.format(UNEXPECTED_VALUE, property));
			}
		}

		/**
		 * Deserialize by ignoring the {@link TypeDeserializer}, as URIs will either resolve to {@literal null} or a
		 * concrete instance anyway.
		 *
		 * @see com.fasterxml.jackson.databind.deser.std.StdDeserializer#deserializeWithType(com.fasterxml.jackson.core.JsonParser,
		 *      com.fasterxml.jackson.databind.DeserializationContext,
		 *      com.fasterxml.jackson.databind.jsontype.TypeDeserializer)
		 */
		@Override
		public Object deserializeWithType(JsonParser jp, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
				throws IOException {
			return deserialize(jp, ctxt);
		}
	}

	/**
	 * {@link ValueInstantiator} to create collection or map instances based on the type of the configured
	 * {@link PersistentProperty}.
	 *
	 * @author Oliver Gierke
	 */
	private static class CollectionValueInstantiator extends ValueInstantiator {

		private final PersistentProperty<?> property;

		/**
		 * Creates a new {@link CollectionValueInstantiator} for the given {@link PersistentProperty}.
		 *
		 * @param property must not be {@literal null} and must be a collection.
		 */
		public CollectionValueInstantiator(PersistentProperty<?> property) {

			Assert.notNull(property, "Property must not be null!");
			Assert.isTrue(property.isCollectionLike() || property.isMap(), "Property must be a collection or map property!");

			this.property = property;
		}

		/*
		 * (non-Javadoc)
		 * @see com.fasterxml.jackson.databind.deser.ValueInstantiator#getValueTypeDesc()
		 */
		@Override
		public String getValueTypeDesc() {
			return property.getType().getName();
		}

		/*
		 * (non-Javadoc)
		 * @see com.fasterxml.jackson.databind.deser.ValueInstantiator#createUsingDefault(com.fasterxml.jackson.databind.DeserializationContext)
		 */
		@Override
		public Object createUsingDefault(DeserializationContext ctxt) throws IOException, JsonProcessingException {

			Class<?> collectionOrMapType = property.getType();

			return property.isMap() ? CollectionFactory.createMap(collectionOrMapType, 0)
					: CollectionFactory.createCollection(collectionOrMapType, 0);
		}
	}

	private static class RepositoryInvokingDeserializer extends StdScalarDeserializer<Object> {

		private static final long serialVersionUID = -3033458643050330913L;
		private final RepositoryInvoker invoker;

		private RepositoryInvokingDeserializer(RepositoryInvokerFactory factory, PersistentProperty<?> property) {

			super(property.getActualType());
			this.invoker = factory.getInvokerFor(_valueClass);
		}

		/*
		 * (non-Javadoc)
		 * @see com.fasterxml.jackson.databind.JsonDeserializer#deserialize(com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.DeserializationContext)
		 */
		@Override
		public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
			return invoker.invokeFindOne(p.getValueAsString());
		}
	}

}
