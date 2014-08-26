package org.infinispan.objectfilter.impl.predicateindex;

import org.infinispan.objectfilter.impl.FilterSubscriptionImpl;
import org.infinispan.objectfilter.impl.MetadataAdapter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * An attribute node represents a single attribute and keeps track of subscribed predicates and projections.
 *
 * @author anistor@redhat.com
 * @since 7.0
 */
public class AttributeNode<AttributeMetadata, AttributeId extends Comparable<AttributeId>> {

   // this is never null, except for the root node
   private final AttributeId attribute;

   // property metadata for an intermediate or leaf node. This is never null, except for the root node
   private final AttributeMetadata metadata;

   private final MetadataAdapter<?, AttributeMetadata, AttributeId> metadataAdapter;

   // this is never null, except for the root node
   private final AttributeNode<AttributeMetadata, AttributeId> parent;

   /**
    * Child attributes.
    */
   private Map<AttributeId, AttributeNode<AttributeMetadata, AttributeId>> children;

   /**
    * Root node must not have predicates. This field is always null for root node. Non-leaf nodes can only have
    * 'isEmpty' or 'isNull' predicates.
    */
   private Predicates predicates;

   /**
    * The list of all subscribed projections. This field is always null for root node. Only leaf nodes can have
    * projections.
    */
   private Projections projections;

   /**
    * Constructor used only for the root node.
    */
   protected AttributeNode(MetadataAdapter<?, AttributeMetadata, AttributeId> metadataAdapter) {
      this.attribute = null;
      this.parent = null;
      this.metadataAdapter = metadataAdapter;
      this.metadata = null;
   }

   private AttributeNode(AttributeId attribute, AttributeNode<AttributeMetadata, AttributeId> parent) {
      this.attribute = attribute;
      this.parent = parent;
      metadataAdapter = parent.metadataAdapter;
      metadata = metadataAdapter.makeChildAttributeMetadata(parent.metadata, attribute);
   }

   public AttributeId getAttribute() {
      return attribute;
   }

   public AttributeMetadata getMetadata() {
      return metadata;
   }

   public AttributeNode<AttributeMetadata, AttributeId> getParent() {
      return parent;
   }

   public Iterator<AttributeNode<AttributeMetadata, AttributeId>> getChildrenIterator() {
      return children != null ? children.values().iterator() : Collections.<AttributeNode<AttributeMetadata, AttributeId>>emptyList().iterator();
   }

   /**
    * @param attribute
    * @return the child or null if not found
    */
   public AttributeNode<AttributeMetadata, AttributeId> getChild(AttributeId attribute) {
      if (children != null) {
         return children.get(attribute);
      }
      return null;
   }

   public int getNumChildren() {
      return children != null ? children.size() : 0;
   }

   public boolean hasPredicates() {
      return predicates != null && !predicates.isEmpty();
   }

   public boolean hasProjections() {
      return projections != null && projections.hasProjections();
   }

   public void processValue(Object attributeValue, MatcherEvalContext<?, AttributeMetadata, AttributeId> ctx) {
      if (projections != null) {
         projections.processProjections(ctx, attributeValue);
      }
      if (predicates != null) {
         predicates.notifyMatchingSubscribers(ctx, attributeValue);
      }
   }

   /**
    * Add a child node. If the child already exists it just increments its usage counter and returns the existing
    * child.
    *
    * @param attribute
    * @return the added or existing child
    */
   public AttributeNode<AttributeMetadata, AttributeId> addChild(AttributeId attribute) {
      AttributeNode<AttributeMetadata, AttributeId> child;
      if (children == null) {
         children = new HashMap<AttributeId, AttributeNode<AttributeMetadata, AttributeId>>();
         child = new AttributeNode<AttributeMetadata, AttributeId>(attribute, this);
         children.put(attribute, child);
      } else {
         child = children.get(attribute);
         if (child == null) {
            child = new AttributeNode<AttributeMetadata, AttributeId>(attribute, this);
            children.put(attribute, child);
         }
      }
      return child;
   }

   /**
    * Decrement the usage counter of a child node and remove it if no usages remain. The removal works recursively up
    * the path to the root.
    *
    * @param attribute the attribute of the child to be removed
    */
   public void removeChild(AttributeId attribute) {
      if (children == null) {
         throw new IllegalArgumentException("No child found : " + attribute);
      }
      AttributeNode<AttributeMetadata, AttributeId> child = children.get(attribute);
      if (child == null) {
         throw new IllegalArgumentException("No child found : " + attribute);
      }
      children.remove(attribute);
   }

   public void addPredicateSubscription(PredicateIndex.PredicateSubscription subscription) {
      if (predicates == null) {
         predicates = new Predicates(metadataAdapter.isComparableProperty(metadata));
      }
      predicates.addPredicateSubscription(subscription);
   }

   public void removePredicateSubscription(PredicateIndex.PredicateSubscription subscription) {
      if (predicates != null) {
         predicates.removePredicateSubscription(subscription);
      } else {
         throw new IllegalStateException("Reached illegal state");
      }
   }

   public void addProjection(FilterSubscriptionImpl filterSubscription, int position) {
      if (projections == null) {
         projections = new Projections();
      }
      projections.addProjection(filterSubscription, position);
   }

   public void removeProjections(FilterSubscriptionImpl filterSubscription) {
      if (projections != null) {
         projections.removeProjections(filterSubscription);
      } else {
         throw new IllegalStateException("Reached illegal state");
      }
   }

   @Override
   public String toString() {
      return "AttributeNode(" + attribute + ')';
   }
}
