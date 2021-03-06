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

import com.peregrine.admin.replication.AbstractionReplicationService;
import com.peregrine.admin.replication.ReferenceLister;
import com.peregrine.admin.replication.impl.mock.MockRequestPathInfo;
import com.peregrine.admin.replication.impl.mock.MockSlingHttpServletRequest;
import com.peregrine.admin.replication.impl.mock.MockSlingHttpServletResponse;
import com.peregrine.commons.util.PerUtil;
import com.peregrine.commons.util.PerUtil.ResourceChecker;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.engine.SlingRequestProcessor;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import static com.peregrine.admin.replication.ReplicationUtil.updateReplicationProperties;
import static com.peregrine.commons.util.PerConstants.ASSET_PRIMARY_TYPE;
import static com.peregrine.commons.util.PerConstants.JCR_CONTENT;
import static com.peregrine.commons.util.PerConstants.NT_FILE;
import static com.peregrine.commons.util.PerConstants.NT_FOLDER;
import static com.peregrine.commons.util.PerConstants.SLING_FOLDER;
import static com.peregrine.commons.util.PerConstants.SLING_ORDERED_FOLDER;
import static com.peregrine.commons.util.PerUtil.RENDITIONS;
import static com.peregrine.commons.util.PerUtil.getPrimaryType;
import static com.peregrine.commons.util.PerUtil.getResourceType;
import static com.peregrine.commons.util.PerUtil.isEmpty;
import static com.peregrine.commons.util.PerUtil.isNotEmpty;

/**
 * Base Class for External File System / Storage Replications
 *
 * Created by Andreas Schaefer on 5/25/17.
 */
public abstract class BaseFileReplicationService
    extends AbstractionReplicationService
{
    private static final List<Pattern> NAME_PATTERNS = new ArrayList<>();
    // List of all resources that are excluded from handling
    private static final List<String> EXCLUDED_RESOURCES = new ArrayList<>();

    static {
        NAME_PATTERNS.add(Pattern.compile(".*\\.data\\.json"));
        EXCLUDED_RESOURCES.add(JCR_CONTENT);
        EXCLUDED_RESOURCES.add(RENDITIONS);
    }

    @Override
    public List<Resource> replicate(Resource startingResource, boolean deep)
        throws ReplicationException
    {
        log.trace("Replicate Resource: '{}', deep: '{}'", startingResource, deep);
        List<Resource> referenceList = getReferenceLister().getReferenceList(true, startingResource, true);
        List<Resource> replicationList = new ArrayList<>();
        ResourceChecker resourceChecker = new ResourceChecker() {
            @Override
            public boolean doAdd(Resource resource) {
                return !EXCLUDED_RESOURCES.contains(resource.getName());
            }

            @Override
            public boolean doAddChildren(Resource resource) {
                return !EXCLUDED_RESOURCES.contains(resource.getName());
            }
        };
        // Need to check this list of they need to be replicated first
        for(Resource resource: referenceList) {
            if(resourceChecker.doAdd(resource)) {
                replicationList.add(resource);
            }
        }
        // This only returns the referenced resources. Now we need to check if there are any JCR Content nodes to be added as well
        for(Resource reference: new ArrayList<Resource>(replicationList)) {
            PerUtil.listMissingResources(reference, replicationList, resourceChecker, false);
        }
        PerUtil.listMissingResources(startingResource, replicationList, resourceChecker, deep);
        return replicate(replicationList);
    }

    @Override
    public List<Resource> deactivate(Resource startingResource)
        throws ReplicationException
    {
        List<Resource> replicationList = new ArrayList<>(Arrays.asList(startingResource));
        return deactivate(startingResource, replicationList);
    }

    @Override
    public List<Resource> replicate(List<Resource> resourceList) throws ReplicationException {
        List<Resource> answer = new ArrayList<>();
        log.trace("Replicate Resource List: '{}'", resourceList);
        // Replicate the resources
        ResourceResolver resourceResolver = null;
        for(Resource item: resourceList) {
            if(item != null) {
                resourceResolver = item.getResourceResolver();
                break;
            }
        }
        if(resourceResolver != null) {
            Session session = resourceResolver.adaptTo(Session.class);
            for(Resource item: resourceList) {
                if(item != null) {
                    // Ignore jcr:content as they cannot be rendered to the FS (if needed then we need to map the file names)
                    //AS TODO: Check if the resource name can be mapped to a file name and if not ignore it. Also make sure we ignore nodes like jcr:content
                    if(!item.getPath().contains(JCR_CONTENT)) {
                        handleParents(item.getParent());
                        replicateResource(item);
                        answer.add(item);
                    }
                }
            }
            try {
                session.save();
            } catch(RepositoryException e) {
                log.warn("Failed to save changes replicate parents", e);
            }
        }
        return answer;
    }

    /**
     * This method deactivates the given resource to deactivate it and then updates
     * the given list of source resources with the replication properties
     *
     * @param toBeDeleted The staring resource to be removed which removes all its children
     * @param resourceList The list of the source dependencies to be updated
     * @return List of all updated source dependencies
     * @throws ReplicationException
     */
    public List<Resource> deactivate(Resource toBeDeleted, List<Resource> resourceList) throws ReplicationException {
        List<Resource> answer = new ArrayList<>();
        String primaryType = getPrimaryType(toBeDeleted);
        if(ASSET_PRIMARY_TYPE.equals(primaryType)) {
            removeReplica(toBeDeleted, null, false);
            answer.add(toBeDeleted);
        } else if(primaryType.startsWith("per:")) {
            removeReplica(toBeDeleted, NAME_PATTERNS, false);
            answer.add(toBeDeleted);
        } else if(primaryType.equals(NT_FOLDER) || primaryType.equals(SLING_FOLDER) || primaryType.equals(SLING_ORDERED_FOLDER)) {
            removeReplica(toBeDeleted, null, true);
            answer.add(toBeDeleted);
        }
        return answer;
    }

    /** @return Sling Request Processor to render pages **/
    abstract SlingRequestProcessor getRequestProcessor();
    /** @return Reference Lister to find referencing nodes **/
    abstract ReferenceLister getReferenceLister();

    /** @return True of the folder is already created on the Target **/
    abstract boolean isFolderOnTarget(String path);

    /** Create a folder on the target based on the given Path **/
    abstract void createTargetFolder(String path) throws ReplicationException;

    private void handleParents(Resource resource) throws ReplicationException {
        // Go through all its parents and make sure the folder does exist
        if(!isFolderOnTarget(resource.getPath())) {
            Resource parent = resource.getParent();
            if(parent != null) {
                handleParents(parent);
            }
            createTargetFolder(resource.getPath());
        }
    }

    private void replicateResource(Resource resource) throws ReplicationException {
        // Need to figure out the type and replicate accordingly
        String primaryType = getPrimaryType(resource);
        if(ASSET_PRIMARY_TYPE.equals(primaryType)) {
            replicateAsset(resource);
        } else {
            replicatePerResource(resource, false);
        }
    }

    /** @return Map listing all extensions and the primary types of all nodes that are exported with that extension **/
    abstract List<ExportExtension> getExportExtensions();
    /** @return A list of all mandatory renditions which are created during the replication if not already there **/
    abstract List<String> getMandatoryRenditions();

    private void replicateAsset(Resource resource) throws ReplicationException {
        // Get the image data of the resource and write to the target
        byte[] imageContent = renderRawResource(resource, "", false);
        storeRendering(resource, "", imageContent);
        // Loop over all existing renditions and write the image data to the target
        List<String> checkRenditions = new ArrayList<>(getMandatoryRenditions());
        Resource renditions = resource.getChild(RENDITIONS);
        if(renditions != null) {
            for(Resource rendition: renditions.getChildren()) {
                if(NT_FILE.equals(getPrimaryType(rendition))) {
                    try {
                        imageContent = renderRawResource(resource, "rendition.json/" + rendition.getName(), true);
                        storeRendering(resource, rendition.getName(), imageContent);
                        checkRenditions.remove(rendition.getName());
                    } catch(ReplicationException e) {
                        log.warn("Rendition: '{}' failed with message: '{}'", rendition.getPath(), e.getMessage());
                        log.warn("Rendition Failure", e);
                    }
                }
            }
        }
        // Loop over all remaining mandatory renditions and write the image data to the target
        for(String renditionName: checkRenditions) {
            try {
                imageContent = renderRawResource(resource, "rendition.json/" + renditionName, true);
                // Get rendition
                if(renditions == null) { renditions = resource.getChild(RENDITIONS); }
                if(renditions != null) {
                    Resource rendition = renditions.getChild(renditionName);
                    if(rendition != null) {
                        storeRendering(resource, rendition.getName(), imageContent);
                    }
                }
            } catch(ReplicationException e) {
                log.warn("Rendition: '{}' failed with message: '{}'", renditionName, e.getMessage());
                log.warn("Rendition Failure", e);
            }
        }
    }

    /**
     * Store the given Content Rendering on the target
     * @param resource Resource that is exported
     * @param extension File Extension (without a leading dot)
     * @param content String content of the rendering
     * @return Path to the Stored Rendition used for the Rendition Ref property
     * @throws ReplicationException if the writing of the content failed
     */
    abstract String storeRendering(Resource resource, String extension, String content) throws ReplicationException;
    /**
     * Store the given Asset Rendering on the target
     * @param resource Resource that is exported
     * @param extension File Extension (without a leading dot)
     * @param content Asset content of the rendering
     * @return Path to the Stored Rendition used for the Rendition Ref property
     * @throws ReplicationException if the writing of the content failed
     */
    abstract String storeRendering(Resource resource, String extension, byte[] content) throws ReplicationException;

    /**
     * Removes a given resource from the target
     * @param resource Source Resource which replica is to be removed
     * @param namePattern List of Regex Pattern that will find the replica
     * @param isFolder If true this is a folder to be removed
     * @throws ReplicationException If the removal fails
     */
    abstract void removeReplica(Resource resource, final List<Pattern> namePattern, boolean isFolder) throws ReplicationException;

    private void replicatePerResource(Resource resource, boolean post) throws ReplicationException {
        log.trace("Replicate Resource: '{}', Post: '{}'", resource.getPath(), post);
        for(ExportExtension exportExtension: getExportExtensions()) {
            String extension = exportExtension.getName();
            log.trace("Handle Extension: '{}'", extension);
            boolean raw = extension.endsWith("~raw");
            if(raw) {
                extension = extension.substring(0, extension.length() - "~raw".length());
            }
            if("*".equals(extension)) {
                extension = "";
            }
            if(exportExtension.supportsResource(resource)) {
                Object renderingContent = null;
                try {
                    if(raw) {
                        log.trace("Before Rendering Raw Resource With Extension: '{}'", extension);
                        renderingContent = renderRawResource(resource, extension, post);
                    } else {
                        log.trace("Before Rendering String Resource With Extension: '{}'", extension);
                        renderingContent = renderResource(resource, extension, post);
                    }
                } catch(ReplicationException e) {
                    log.warn("Rendering of '{}' failed -> ignore it", resource.getPath());
                }
                if(renderingContent != null) {
                    log.trace("Rendered Resource: {}", renderingContent);
                    String path;
                    if(raw) {
                        path = storeRendering(resource, extension, (byte[]) renderingContent);
                    } else {
                        path = storeRendering(resource, extension, (String) renderingContent);
                    }
                    Resource contentResource = resource.getChild(JCR_CONTENT);
                    if(contentResource != null) {
                        updateReplicationProperties(contentResource, path, null);
                    }
                }
            }
        }
    }

    /**
     * Renders the given resource inside this sling instance and returns its byte stream
     * @param resource Resource to be rendered
     * @param extension Extension of the rendering request
     * @param post True if this is a POST request
     * @return Byte Array of the rendered resource
     * @throws ReplicationException If the rendering failed
     */
    private byte[] renderRawResource(Resource resource, String extension, boolean post) throws ReplicationException {
        return renderResource0(resource, extension, post).getOutput();
    }

    /**
     * Renders the given resource inside this sling instance and returns its byte stream
     * @param resource Resource to be rendered
     * @param extension Extension of the rendering request
     * @param post True if this is a POST request
     * @return String content of the rendered resource
     * @throws ReplicationException If the rendering failed
     */
    private String renderResource(Resource resource, String extension, boolean post) throws ReplicationException {
        return renderResource0(resource, extension, post).getOutputAsString();
    }

    private MockSlingHttpServletResponse renderResource0(Resource resource, String extension, boolean post) throws ReplicationException {
        try {
            MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resource.getResourceResolver());
            MockRequestPathInfo mrpi = (MockRequestPathInfo) req.getRequestPathInfo();
            mrpi.setResourcePath(resource.getPath());
            if(isEmpty(extension)) {
                extension = "";
//            } else if(!extension.startsWith(".")) {
//                extension = "." + extension;
            }
            log.trace("Render Resource Request Extension: '{}'", extension);
            mrpi.setExtension(extension);
            String requestPath = resource.getPath() + "." + extension;
            log.trace("Render Resource Request Path: '{}'", mrpi);
            MockSlingHttpServletResponse resp = new MockSlingHttpServletResponse();
            getRequestProcessor().processRequest(req, resp, resource.getResourceResolver());
            log.trace("Response Status: '{}'", resp.getStatus());
            //AS TODO: do we need to support redirects (301 / 302)
            if(resp.getStatus() != 200) {
                String content = resp.getOutputAsString();
                log.error("Request of: '{}' failed (status: {}). Output : '{}'", requestPath, resp.getStatus(), content);
                throw new ReplicationException("Request: '" + requestPath + "' failed with status: '" + resp.getStatus() + "'");
            } else {
                return resp;
            }
        } catch(UnsupportedEncodingException e) {
            throw new ReplicationException("Unsupported Encoding while creating the Render Response", e);
        } catch(ServletException | IOException e) {
            throw new ReplicationException("Failed to render resource: " + resource.getPath(), e);
        }
    }

    public static class ExportExtension {
        private String name;
        private List<String> types = new ArrayList<>();
        private boolean exportFolders = false;

        public ExportExtension(String name, List<String> types) {
            if(isEmpty(name)) {
                throw new IllegalArgumentException("Extension Name must be provided");
            }
            if(types == null || types.isEmpty()) {
                throw new IllegalArgumentException("Extension Types must be provided");
            }
            this.name = name;
            this.types = types;
        }

        public String getName() {
            return name;
        }

        public boolean supportsResource(Resource resource) {
            String primaryType = getPrimaryType(resource);
            if(types.contains(primaryType)) { return true; }
            String slingResourceType = getResourceType(resource);
            if(types.contains(slingResourceType)) { return true; }
            return false;
        }

        public boolean isExportFolders() {
            return exportFolders;
        }

        public ExportExtension setExportFolders(boolean exportFolders) {
            this.exportFolders = exportFolders;
            return this;
        }
    }
}
