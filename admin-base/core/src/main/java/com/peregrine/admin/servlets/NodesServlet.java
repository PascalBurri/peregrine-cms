package com.peregrine.admin.servlets;

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

import com.peregrine.commons.servlets.AbstractBaseServlet;
import com.peregrine.commons.util.PerUtil;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.factory.ModelFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.Servlet;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import static com.peregrine.admin.servlets.AdminPaths.RESOURCE_TYPE_NODES;
import static com.peregrine.commons.util.PerConstants.*;
import static com.peregrine.commons.util.PerUtil.EQUALS;
import static com.peregrine.commons.util.PerUtil.GET;
import static com.peregrine.commons.util.PerUtil.PER_PREFIX;
import static com.peregrine.commons.util.PerUtil.PER_VENDOR;
import static com.peregrine.commons.util.PerUtil.getProperties;
import static com.peregrine.commons.util.PerUtil.isPrimaryType;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static org.apache.sling.api.servlets.ServletResolverConstants.SLING_SERVLET_METHODS;
import static org.apache.sling.api.servlets.ServletResolverConstants.SLING_SERVLET_RESOURCE_TYPES;
import static org.osgi.framework.Constants.SERVICE_DESCRIPTION;
import static org.osgi.framework.Constants.SERVICE_VENDOR;

/**
 * List all the resources part of the given Path
 *
 * The API Definition can be found in the Swagger Editor configuration:
 *    ui.apps/src/main/content/jcr_root/api/definintions/admin.yaml
 */
@Component(
    service = Servlet.class,
    property = {
        SERVICE_DESCRIPTION + EQUALS + PER_PREFIX + "Nodes servlet",
        SERVICE_VENDOR + EQUALS + PER_VENDOR,
        SLING_SERVLET_METHODS + EQUALS + GET,
        SLING_SERVLET_RESOURCE_TYPES + EQUALS + RESOURCE_TYPE_NODES
    }
)
@SuppressWarnings("serial")
public class NodesServlet extends AbstractBaseServlet {

    private static DateFormat formatter = new SimpleDateFormat(ECMA_DATE_FORMAT, ECMA_DATE_FORMAT_LOCALE);

    @Reference
    ModelFactory modelFactory;

    @Override
    protected Response handleRequest(Request request) throws IOException {
        String path = request.getParameter("path");
        if(path == null || path.isEmpty()) {
            return new ErrorResponse().setHttpErrorCode(SC_BAD_REQUEST).setErrorMessage("No Path provided").setRequestPath(path);
        }
        String[] segments = path.split("/");
        logger.debug("lookup path {}, {}", path, segments.length);
        JsonResponse answer = new JsonResponse();
        convertResource(answer, request.getResourceResolver(), segments, 1, path);
        return answer;
    }

    private void convertResource(JsonResponse json, ResourceResolver rs, String[] segments, int pos, String fullPath) throws IOException {
        String path = "";
        for(int i = 1; i <= pos; i++) {
            path += "/" + segments[i];
        }
        logger.debug("looking up {}", path);
        Resource res = rs.getResource(path);
        json.writeAttribute("name",res.getName());
        json.writeAttribute("path",res.getPath());
        writeProperties(res, json);
        json.writeArray("children");
        Iterable<Resource> children = res.getChildren();
        for(Resource child : children) {
            if(fullPath.startsWith(child.getPath())) {
                json.writeObject();
                convertResource(json, rs, segments, pos+1, fullPath);
                json.writeClose();
            } else {
                if(!JCR_CONTENT.equals(child.getName())) {
                    json.writeObject();
                    json.writeAttribute("name",child.getName());
                    json.writeAttribute("path",child.getPath());
                    writeProperties(child, json);
                    if(isPrimaryType(child, ASSET_PRIMARY_TYPE)) {
                        String mimeType = child.getChild(JCR_CONTENT).getValueMap().get(JCR_MIME_TYPE, String.class);
                        json.writeAttribute("mimeType", mimeType);
                    }
                    if(isPrimaryType(child, PAGE_PRIMARY_TYPE)) {
                        Resource content = child.getChild(JCR_CONTENT);
                        if(content != null) {
                            for (String key: content.getValueMap().keySet()) {
                                if(key.equals(JCR_TITLE)) {
                                    String title = content.getValueMap().get(JCR_TITLE, String.class);
                                    json.writeAttribute("title", title);
                                } else {
                                    if(key.indexOf(":") < 0) {
                                        json.writeAttribute(key, content.getValueMap().get(key, String.class));
                                    }
                                }
                            }
                            String component = PerUtil.getComponentNameFromResource(content);
                            json.writeAttribute("component", component);
                        } else {
                            logger.debug("No Content Child found for: '{}'", child.getPath());
                        }
                    }
                    json.writeClose();
                }
            }
        }
        json.writeClose();
    }

    private void writeProperties(Resource resource, JsonResponse json) throws IOException {
        ValueMap properties = resource.getValueMap();
        writeIfFound(json, JCR_PRIMARY_TYPE, properties, "resourceType");
        writeIfFound(json, JCR_CREATED, properties);
        writeIfFound(json, JCR_CREATED_BY, properties);
        writeIfFound(json, JCR_LAST_MODIFIED, properties);
        writeIfFound(json, JCR_LAST_MODIFIED_BY, properties);
        writeIfFound(json, JCR_LAST_MODIFIED_BY, properties);
        writeIfFound(json, ALLOWED_OBJECTS, properties);

        // For the Replication data we need to obtain the content properties
        ValueMap contentProperties = getProperties(resource);
        if(contentProperties != null) {
            String replicationDate = writeIfFound(json, PER_REPLICATED, contentProperties);
            writeIfFound(json, PER_REPLICATED_BY, contentProperties);
            //        String replicationLocation = writeIfFound(json, PER_REPLICATION, properties);
            String replicationLocationRef = writeIfFound(json, PER_REPLICATION_REF, contentProperties);
            if(replicationDate != null && !replicationDate.isEmpty()) {
                String status = "activated";
                if(replicationLocationRef == null || replicationLocationRef.isEmpty()) {
                    status = "deactivated";
                }
                json.writeAttribute("ReplicationStatus", status);
            }
        }
    }

    private String writeIfFound(JsonResponse json, String propertyName, ValueMap properties) throws IOException {
        return writeIfFound(json, propertyName, properties, propertyName);
    }

    private static final String[] OMIT_PREFIXES = new String[] { "jcr:", "per:" };

    private String writeIfFound(JsonResponse json, String propertyName, ValueMap properties, String responseName) throws IOException {
        Object value = properties.get(propertyName);
        String data;
        if(value instanceof Calendar) {
            data = formatter.format(((Calendar) value).getTime());
        } else {
            data = properties.get(propertyName, String.class);
        }
        if(data != null) {
            String name = responseName;
            for(String omitPrefix: OMIT_PREFIXES) {
                if(name.startsWith(omitPrefix)) {
                    name = name.substring(omitPrefix.length());
                    break;
                }
            }
            json.writeAttribute(name, data);
        }
        return data;
    }
}

