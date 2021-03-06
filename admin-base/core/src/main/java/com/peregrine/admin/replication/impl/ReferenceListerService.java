package com.peregrine.admin.replication.impl;

/*-
 * #%L
 * admin base - Core
 * %%
 * Copyright (C) 2017 headwire inc.
 * %%
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */

import com.peregrine.admin.replication.Reference;
import com.peregrine.admin.replication.ReferenceLister;
import com.peregrine.commons.util.PerUtil;
import com.peregrine.commons.util.PerUtil.MissingOrOutdatedResourceChecker;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.peregrine.commons.util.PerConstants.JCR_CONTENT;

/**
 * Created by Andreas Schaefer on 5/25/17.
 */
@Component(
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    service = ReferenceLister.class,
    immediate = true
)
@Designate(ocd = ReferenceListerService.Configuration.class)
public class ReferenceListerService
    implements ReferenceLister
{
    @ObjectClassDefinition(
        name = "Peregrine: Reference List Provider",
        description = "Provides a list of referenced resources for a given resource"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "ReferencePrefix",
            description = "List of Prefixes to find references in a resource",
            required = true
        )
        String[] referencePrefix() default "/content/";
        @AttributeDefinition(
            name = "ReferencedByRoot",
            description = "List of Roots to look for the referenced by resources",
            required = true
        )
        String[] referencedByRoot() default "/content";
    }

    private final Logger log = LoggerFactory.getLogger(getClass());

    private List<String> referencePrefixList = new ArrayList<>();
    private List<String> referencedByRootList = new ArrayList<>();

    @Override
    public List<Resource> getReferenceList(boolean traverseDeep, Resource resource, boolean deep) {
        return getReferenceList(traverseDeep, resource, deep, null, null);
    }

    @Override
    public List<Resource> getReferenceList(boolean traverseDeep, Resource resource, boolean deep, Resource source, Resource target) {
        List<Resource> answer = new ArrayList<>();
        traverse(traverseDeep, resource, answer, deep, source, target);
        return answer;
    }

    public List<Reference> getReferencedByList(Resource resource) {
        List<Reference> answer = new ArrayList<>();
        for(String root: referencedByRootList) {
            Resource rootResource = resource != null ? resource.getResourceResolver().getResource(root) : null;
            if(rootResource != null) {
                traverseTreeReverse(rootResource, resource.getPath(), answer);
            }
        }
        return answer;
    }

    private void traverse(boolean traverseDeep, Resource resource, List<Resource> response, boolean deep, Resource source, Resource target) {
        if(resource != null) {
            parseProperties(traverseDeep, resource, response, false, source, target);
            Resource jcrContent = resource.getChild(JCR_CONTENT);
            if(!deep) {
                if(jcrContent != null) {
                    parseProperties(traverseDeep, jcrContent, response, deep, source, target);
                    // Loop of all its children
                    traverseTree(traverseDeep, jcrContent, response, deep, source, target);
                }
            } else {
                Iterable<Resource> children = resource.getChildren();
                for(Resource child : children) {
                    parseProperties(traverseDeep, child, response, deep, source, target);
                    // Loop of all its children
                    traverseTree(traverseDeep, child, response, deep, source, target);
                }
            }
        }
    }

    private void traverseTree(boolean traverseDeep, Resource resource, List<Resource> response, boolean deep, Resource source, Resource target) {
        for(Resource child: resource.getChildren()) {
            parseProperties(traverseDeep, child, response, deep, source, target);
            traverseTree(traverseDeep, child, response, deep, source, target);
        }
    }

    private void parseProperties(boolean traverseDeep, Resource resource, List<Resource> response, boolean deep, Resource source, Resource target) {
        ValueMap properties = resource.getValueMap();
        for(Object item: properties.values()) {
            String value = item + "";
            for(String prefix: referencePrefixList) {
                if(value.startsWith(prefix)) {
                    String resourcePath = value;
                    log.trace("Found Reference Resource Path: '{}'", resourcePath);
                    Resource child = null;
                    if(resourcePath.startsWith("/")) {
                        child = resource.getResourceResolver().getResource(value);
                    } else {
                        child = resource.getChild(resourcePath);
                    }
                    if(child != null) {
                        // Check if the resource is not already listed in there
                        if(containsResource(response, child)) {
                            log.info("Resource is already in the list: '{}'", child);
                        } else {
                            if(source  != null && target != null) {
                                PerUtil.listMissingParents(child, response, source, new MissingOrOutdatedResourceChecker(source, target));
                            }
                            log.trace("Found Reference Resource: '{}'", child);
                            response.add(child);
                            if(traverseDeep) {
                                traverse(traverseDeep, child, response, deep, source, target);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean containsResource(List<Resource> resources, Resource check) {
        boolean answer = false;
        String checkPath = check.getPath();
        for(Resource item: resources) {
            if(item.getPath().equals(checkPath)) {
                answer = true;
                break;
            }
        }
        return answer;
    }

    private void traverseTreeReverse(Resource resource, String referencePath, List<Reference> response) {
        for(Resource child: resource.getChildren()) {
            parsePropertiesReverse(child, referencePath, response);
            traverseTreeReverse(child, referencePath, response);
        }
    }

    private void parsePropertiesReverse(Resource resource, String referencePath, List<Reference> response) {
        ValueMap properties = resource.getValueMap();
        for(Map.Entry<String, Object> entry: properties.entrySet()) {
//        for(Object item: properties.values()) {
            String name = entry.getKey();
            String value = entry.getValue() + "";
            if(referencePath.equals(value)) {
                // Find the node
                boolean found = false;
                Resource temp = resource;
                while(true) {
                    if(temp.getName().equals(JCR_CONTENT)) {
                        Resource parent = temp.getParent();
                        if(parent != null) {
                            if(!response.contains(parent)) {
                                response.add(new Reference(parent, name, resource));
                            }
                            found = true;
                        } else {
                            log.warn("JCR Content Node: '{}' found but no parent", temp.getPath());
                        }
                        break;
                    } else {
                        temp = temp.getParent();
                        if(temp == null) {
                            break;
                        }
                    }
                }
                if(!found) {
                    // No JCR Content node found so just use this one
                    if(!response.contains(resource)) {
                        response.add(new Reference(resource, name, resource));
                    }
                }
            }
        }
    }

    @Activate
    @SuppressWarnings("unused")
    void activate(Configuration configuration) { setup(configuration); }
    @Modified
    @SuppressWarnings("unused")
    void modified(Configuration configuration) { setup(configuration); }

    private void setup(Configuration configuration) {
        String[] prefixes = configuration.referencePrefix();
        referencePrefixList = new ArrayList<>();
        for(String prefix: prefixes) {
            if(prefix != null && !prefix.isEmpty()) {
                log.debug("Add Reference Prefix: '{}'", prefix);
                referencePrefixList.add(prefix);
            }
        }
        String[] roots = configuration.referencedByRoot();
        referencedByRootList = new ArrayList<>();
        for(String root: roots) {
            if(root != null && !root.isEmpty()) {
                log.debug("Add Referenced By Root: '{}'", root);
                referencedByRootList.add(root);
            }
        }
    }
}
