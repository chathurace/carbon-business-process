/*
 * Copyright 2005-2015 WSO2, Inc. (http://wso2.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.bpmn.extensions.rest;

import com.jayway.jsonpath.JsonPath;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.Expression;
import org.activiti.engine.delegate.JavaDelegate;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;
import org.wso2.carbon.bpmn.extensions.internal.BPMNExtensionsComponent;
import org.wso2.carbon.registry.api.Registry;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.registry.api.Resource;
import org.wso2.carbon.unifiedendpoint.core.UnifiedEndpoint;
import org.wso2.carbon.unifiedendpoint.core.UnifiedEndpointFactory;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Provides REST service invocation support within BPMN processes. It invokes the REST service given by "serviceURL" or "serviceRef" parameters using
 * the HTTP method given as "method" parameter. "serviceURL" parameter can be used to give a URL of a REST service endpoint, which cannot be changed after deployment.
 * "serviceRef" can point to a registry location which contains an endpoint reference as mentioned in https://docs.wso2.com/display/BPS350/Endpoint+References.
 * URLs given in such registry resources can be changed after deployment and the current value of the registry resource will be read before each service invocation.
 * <p/>
 * Optionally, input payload can be provided using the "input" parameter. Output received from the REST service will be assigned to a
 * process variable (as raw content) or parts of the output can be mapped to different process variables. Both these scenarios are illustrated in below examples.
 * <p/>
 * If a failure occurs in REST task, a BPMN error with error code "RestInvokeError" will be thrown. BPMN process can catch this error using an Error Boundary Event associated
 * with the REST service task.
 * <p/>
 * Example with text input and text output:
 * <p/>
 * <serviceTask id="servicetask1" name="REST task1" activiti:class="RESTTask">
 * <extensionElements>
 * <activiti:field name="serviceURL">
 * <activiti:expression>http://10.0.3.1:9773/restSample1_1.0.0/services/rest_sample1/${method}</activiti:expression>
 * </activiti:field>
 * <activiti:field name="basicAuthUsername">
 * <activiti:expression>bobcat</activiti:expression>
 * </activiti:field>
 * <activiti:field name="basicAuthPassword">
 * <activiti:expression>bobcat</activiti:expression>
 * </activiti:field>
 * <activiti:field name="method">
 * <activiti:string><![CDATA[POST]]></activiti:string>
 * </activiti:field>
 * <activiti:field name="input">
 * <activiti:expression>Input for task1</activiti:expression>
 * </activiti:field>
 * <activiti:field name="outputVariable">
 * <activiti:string><![CDATA[v1]]></activiti:string>
 * </activiti:field>
 * <activiti:field name="headers">
 * <activiti:string><![CDATA[key1:value1,key2:value2]]></activiti:string>
 * </activiti:field>
 * </extensionElements>
 * </serviceTask>
 * <p/>
 * Example with JSON input and JSON output mapping and registry based URL:
 * <serviceTask id="servicetask2" name="Rest task2" activiti:class="RESTTask">
 * <extensionElements>
 * <activiti:field name="serviceRef">
 * <activiti:expression>conf:/test1/service2</activiti:expression>
 * </activiti:field>
 * <activiti:field name="method">
 * <activiti:string><![CDATA[POST]]></activiti:string>
 * </activiti:field>
 * <activiti:field name="input">
 * <activiti:expression>{
 * "companyName":"ibm",
 * "industry":"${industry}",
 * "address":{
 * "country":"USA",
 * "state":"${state}"}
 * }
 * </activiti:expression>
 * </activiti:field>
 * <activiti:field name="outputMappings">
 * <activiti:string><![CDATA[var2:customer.name,var3:item.price]]></activiti:string>
 * </activiti:field>
 * </extensionElements>
 * </serviceTask>
 * <p/>
 * Registry endpoint format:
 * <p/>
 * <Endpoint
 */
public class RESTTask implements JavaDelegate {

    private static final Log log = LogFactory.getLog(RESTTask.class);

    private static final String GOVERNANCE_REGISTRY_PREFIX = "gov:/";
    private static final String CONFIGURATION_REGISTRY_PREFIX = "conf:/";
    private static final String REST_INVOKE_ERROR = "RestInvokeError";
    private static final String GET_METHOD = "GET";
    private static final String POST_METHOD = "POST";
    private static final String PUT_METHOD = "PUT";
    private static final String DELETE_METHOD = "DELETE";
    private RESTInvoker restInvoker;

    private Expression serviceURL;
    private Expression basicAuthUsername;
    private Expression basicAuthPassword;
    private Expression serviceRef;
    private Expression method;
    private Expression input;
    private Expression outputVariable;
    private Expression outputMappings;
    private Expression headers;

    @Override
    public void execute(DelegateExecution execution) {
        if (log.isDebugEnabled()) {
            log.debug("Executing RESTInvokeTask " + method.getValue(execution).toString() + " - " +
                    serviceURL.getValue(execution).toString());
        }

        restInvoker = BPMNExtensionsComponent.getRestInvoker();

        String output = "";
        String url = null;
        String bUsername = null;
        String bPassword = null;
        String headerList[] = null;
        try {
            if (serviceURL != null) {
                url = serviceURL.getValue(execution).toString();
                if (basicAuthUsername != null && basicAuthPassword != null) {
                    bUsername = basicAuthUsername.getValue(execution).toString();
                    bPassword = basicAuthPassword.getValue(execution).toString();
                }
            } else if (serviceRef != null) {
                String resourcePath = serviceRef.getValue(execution).toString();
                String registryPath;
                if (resourcePath.startsWith(GOVERNANCE_REGISTRY_PREFIX)) {
                    registryPath = resourcePath.substring(GOVERNANCE_REGISTRY_PREFIX.length());
                } else if (resourcePath.startsWith(CONFIGURATION_REGISTRY_PREFIX)) {
                    registryPath = resourcePath.substring(CONFIGURATION_REGISTRY_PREFIX.length());
                } else {
                    String msg = "Registry type is not specified for service reference in " +
                            getTaskDetails(execution) +
                            ". serviceRef should begin with gov:/ or conf:/ to indicate the registry type.";
                    throw new RESTClientException(msg);
                }
                String tenantId = execution.getTenantId();
                Registry registry = BPMNExtensionsComponent.getRegistryService().getLocalRepository(Integer.parseInt(tenantId));
                //UserRegistry configSystemRegistry = BPMNExtensionsComponent.getRegistryService().getLocalRepository();
                if (log.isDebugEnabled()) {
                    log.debug("Reading endpoint from registry location: " + registryPath + " for task " + getTaskDetails(execution));
                }
                Resource urlResource = registry.get(registryPath);
                if (urlResource != null) {
                    String uepContent = new String((byte[]) urlResource.getContent());

                    UnifiedEndpointFactory uepFactory = new UnifiedEndpointFactory();
                    OMElement uepElement = AXIOMUtil.stringToOM(uepContent);
                    UnifiedEndpoint uep = uepFactory.createEndpoint(uepElement);
                    url = uep.getAddress();
                    bUsername = uep.getAuthorizationUserName();
                    bPassword = uep.getAuthorizationPassword();
                } else {
                    String errorMsg = "Endpoint resource " + registryPath +
                            " is not found. Failed to execute REST invocation in task " + getTaskDetails(execution);
                    throw new RESTClientException(errorMsg);
                }
            } else {
                String urlNotFoundErrorMsg = "Service URL is not provided for " +
                        getTaskDetails(execution) + ". serviceURL or serviceRef must be provided.";
                throw new RESTClientException(urlNotFoundErrorMsg);
            }

            if (headers != null) {
                String headerContent = headers.getValue(execution).toString();
                headerList = headerContent.split(",");
            }

            if (POST_METHOD.equals(method.getValue(execution).toString().trim())) {
                String inputContent = input.getValue(execution).toString();
                output = this.restInvoker.invokePOST(new URI(url), headerList, bUsername, bPassword, inputContent);
            } else if (GET_METHOD.equals(method.getValue(execution).toString().trim())) {
                output = this.restInvoker.invokeGET(new URI(url), headerList, bUsername, bPassword);
            } else if (PUT_METHOD.equals(method.getValue(execution).toString().trim())) {
                String inputContent = input.getValue(execution).toString();
                output = this.restInvoker.invokePUT(new URI(url), headerList, bUsername, bPassword, inputContent);
            } else if (DELETE_METHOD.equals(method.getValue(execution).toString().trim())) {
                output = this.restInvoker.invokeDELETE(new URI(url), headerList, bUsername, bPassword);
            } else {
                String errorMsg = "Unsupported http method. The REST task only supports GET, POST, PUT and DELETE operations";
                throw new RESTClientException(errorMsg);
            }

            if (outputVariable != null) {
                String outVarName = outputVariable.getValue(execution).toString();
                execution.setVariable(outVarName, output);
            } else if (outputMappings != null) {
                try {
                    new JSONObject(output);
                } catch (JSONException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("The payload is XML, hence converting to json before mapping");
                    }
                    output = XML.toJSONObject(output).toString();
                }
                String outMappings = outputMappings.getValue(execution).toString();
                outMappings = outMappings.trim();
                String[] mappings = outMappings.split(",");
                for (String mapping : mappings) {
                    String[] mappingParts = mapping.split(":");
                    String varName = mappingParts[0];
                    String jsonExpression = mappingParts[1];
                    Object value = JsonPath.read(output, jsonExpression);
                    execution.setVariable(varName, value);
                }
            } else {
                String outputNotFoundErrorMsg = "An output variable or outmappings is not provided. " +
                        "Either an output variable or outmappings  must be provided to save " +
                        "the response.";
                throw new RESTClientException(outputNotFoundErrorMsg);
            }
        } catch (RegistryException | XMLStreamException | URISyntaxException | IOException e) {
            String errorMessage = "Failed to execute " + method.getValue(execution).toString() +
                    " " + url + " within task " + getTaskDetails(execution);
            log.error(errorMessage, e);
            throw new RESTClientException(REST_INVOKE_ERROR, errorMessage);
        }
    }

    private String getTaskDetails(DelegateExecution execution) {
        String task = execution.getCurrentActivityId() + ":" + execution.getCurrentActivityName() + " in process instance " + execution.getProcessInstanceId();
        return task;
    }

    public void setServiceURL(Expression serviceURL) {
        this.serviceURL = serviceURL;
    }

    public void setServiceRef(Expression serviceRef) {
        this.serviceRef = serviceRef;
    }

    public void setInput(Expression input) {
        this.input = input;
    }

    public void setOutputVariable(Expression outputVariable) {
        this.outputVariable = outputVariable;
    }

    public void setHeaders(Expression headers) {
        this.headers = headers;
    }

    public void setMethod(Expression method) {
        this.method = method;
    }

    public Expression getOutputMappings() {
        return outputMappings;
    }

    public void setOutputMappings(Expression outputMappings) {
        this.outputMappings = outputMappings;
    }

    public void setBasicAuthUsername(Expression basicAuthUsername) {
        this.basicAuthUsername = basicAuthUsername;
    }

    public void setBasicAuthPassword(Expression basicAuthPassword) {
        this.basicAuthPassword = basicAuthPassword;
    }
}
