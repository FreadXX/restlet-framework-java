/*
 * Copyright 2005-2008 Noelios Consulting.
 * 
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License (the "License"). You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at
 * http://www.opensource.org/licenses/cddl1.txt See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL HEADER in each file and
 * include the License file at http://www.opensource.org/licenses/cddl1.txt If
 * applicable, add the following below this CDDL HEADER, with the fields
 * enclosed by brackets "[]" replaced with your own identifying information:
 * Portions Copyright [yyyy] [name of copyright owner]
 */

package org.restlet.ext.jaxrs;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.Router;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Preference;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.jaxrs.core.CallContext;
import org.restlet.ext.jaxrs.core.HttpHeaders;
import org.restlet.ext.jaxrs.exceptions.IllegalOrNoAnnotationException;
import org.restlet.ext.jaxrs.exceptions.InstantiateParameterException;
import org.restlet.ext.jaxrs.exceptions.InstantiateRootRessourceException;
import org.restlet.ext.jaxrs.exceptions.JaxRsException;
import org.restlet.ext.jaxrs.exceptions.JaxRsRuntimeException;
import org.restlet.ext.jaxrs.exceptions.RequestHandledException;
import org.restlet.ext.jaxrs.impl.MatchingResult;
import org.restlet.ext.jaxrs.impl.PathRegExp;
import org.restlet.ext.jaxrs.provider.JaxRsOutputRepresentation;
import org.restlet.ext.jaxrs.provider.StringProvider;
import org.restlet.ext.jaxrs.util.RemainingPath;
import org.restlet.ext.jaxrs.util.SortedMetadata;
import org.restlet.ext.jaxrs.util.Util;
import org.restlet.ext.jaxrs.util.WrappedClassLoadException;
import org.restlet.ext.jaxrs.util.WrappedLoadException;
import org.restlet.ext.jaxrs.wrappers.HiddenJaxRsRouter;
import org.restlet.ext.jaxrs.wrappers.MessageBodyReader;
import org.restlet.ext.jaxrs.wrappers.MessageBodyReaderSet;
import org.restlet.ext.jaxrs.wrappers.MessageBodyWriter;
import org.restlet.ext.jaxrs.wrappers.MessageBodyWriterSet;
import org.restlet.ext.jaxrs.wrappers.ResourceMethod;
import org.restlet.ext.jaxrs.wrappers.ResourceObject;
import org.restlet.ext.jaxrs.wrappers.RootResourceClass;
import org.restlet.ext.jaxrs.wrappers.SubResourceLocator;
import org.restlet.ext.jaxrs.wrappers.SubResourceMethod;
import org.restlet.ext.jaxrs.wrappers.SubResourceMethodOrLocator;
import org.restlet.resource.Representation;

/**
 * The router choose the JAX-RS resource class and method to use for a request.
 * This class has methods {@link #attach(Class)} and {@link #detach(Class)} like
 * the Restlet {@link Router}. The variable names in this class are often the
 * same as in the JAX-RS-Definition.<br />
 * 
 * LATER The class JaxRsRouter is not thread save while attach or detach
 * classes.
 * 
 * @see <a href="https://jsr311.dev.java.net/"> Java Service Request 311</a>
 *      Because the specification is just under development the link is not set
 *      to the PDF.
 * 
 * @author Stephan Koops
 */
public class JaxRsRouter extends Restlet implements HiddenJaxRsRouter {

    /**
     * Creates a guarded JaxRsRouter. The credentials and the roles are checked
     * by the Authenticator.
     * 
     * @param context
     *                the context from the parent
     * @param authenticator
     *                the Authenticator which checks the credentials and the
     *                roles. Must not be null; see {@link AllowAllAuthenticator},
     *                {@link ForbidAllAuthenticator} or
     *                {@link ThrowExcAuthenticator}.
     * @param loadAllRootResourceClasses
     *                if true, all accessible root resource classes are loaded.
     * @param loadAllProviders
     *                if true, all accessible providers are loaded.
     * @param challangeScheme
     *                the {@link ChallengeScheme}
     * @param realmName
     *                the name of the realm, presented to the client while
     *                requesting the credentials.S
     * @return Returns the Guard. you can attach root resource classes directly.
     *         If you want to set other properties in the {@link JaxRsRouter},
     *         use the method {@link JaxRsGuard#getNext()}.
     */
    public static JaxRsGuard getGuarded(Context context,
            Authenticator authenticator, boolean loadAllRootResourceClasses,
            boolean loadAllProviders, ChallengeScheme challangeScheme,
            String realmName) {
        JaxRsGuard guard = new JaxRsGuard(context, challangeScheme, realmName,
                authenticator);
        guard.setNext(new JaxRsRouter(context, authenticator,
                loadAllRootResourceClasses, loadAllProviders));
        return guard;
    }

    /**
     * This set must only changed by adding or removing a root resource class to
     * this JaxRsRouter.
     * 
     * @see #attach(Class)
     * @see #detach(Class)
     */
    private Set<RootResourceClass> rootResourceClasses = new HashSet<RootResourceClass>();

    private Authenticator authenticator;

    private MessageBodyReaderSet messageBodyReaders = new MessageBodyReaderSet();

    private MessageBodyWriterSet messageBodyWriters = new MessageBodyWriterSet();

    /**
     * The default Restlet used when a root resource can not be found.
     * 
     * @see #errorRestletRootResourceNotFound
     */
    public static final ReturnStatusRestlet DEFAULT_ROOT_RESOURCE_NOT_FOUND_RESTLET = new ReturnStatusRestlet(
            new Status(Status.CLIENT_ERROR_NOT_FOUND,
                    "Root resource class not found"));

    /**
     * The default Restlet used when a (sub) resource can not be found.
     * 
     * @see #errorRestletResourceNotFound
     */
    public static final ReturnStatusRestlet DEFAULT_RESOURCE_NOT_FOUND_RESTLET = new ReturnStatusRestlet(
            new Status(Status.CLIENT_ERROR_NOT_FOUND,
                    "Resource class not found"));

    /**
     * The default Restlet used when a (sub) resource method can not be found.
     * 
     * @see #errorRestletResourceMethodNotFound
     */
    public static final ReturnStatusRestlet DEFAULT_RESOURCE_METHOD_NOT_FOUND_RESTLET = new ReturnStatusRestlet(
            new Status(Status.CLIENT_ERROR_NOT_FOUND,
                    "Resource method not found or it is not public"));

    /**
     * The default Restlet used when multiple possible resource methods was
     * found.
     * 
     * @see #errorRestletMultipleResourceMethods
     */
    public static final ReturnStatusRestlet DEFAULT_MULTIPLE_RESOURCE_METHODS = new ReturnStatusRestlet(
            new Status(Status.SERVER_ERROR_INTERNAL,
                    "Multiple possible resource methods found"));

    /**
     * The default Restlet used when multiple root resource were found.
     * 
     * @see #errorRestletMultipleRootResourceClasses
     */
    public static final ReturnStatusRestlet DEFAULT_MULTIPLE_ROOT_RESOURCE_CLASSES = new ReturnStatusRestlet(
            new Status(Status.SERVER_ERROR_INTERNAL,
                    "Multiple possible root resource classes found"));

    /**
     * The default Restlet used when the method is not allwed on the resource.
     * 
     * @see #errorRestletMethodNotAllowed
     */
    public static final ReturnStatusRestlet DEFAULT_METHOD_NOT_ALLOWED_RESTLET = new ReturnStatusRestlet(
            Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);

    /**
     * The default Restlet used when the media type is not supported
     * 
     * @see #errorRestletUnsupportedMediaType
     */
    public static final ReturnStatusRestlet DEFAULT_UNSUPPORTED_MEDIA_TYPE_RESTLET = new ReturnStatusRestlet(
            Status.CLIENT_ERROR_UNSUPPORTED_MEDIA_TYPE);

    /**
     * The default Restlet used when the request is not acceptable.
     * 
     * @see #errorRestletRootResourceNotFound
     */
    public static final ReturnStatusRestlet DEFAULT_NOT_ACCEPTABLE_RESTLET = new ReturnStatusRestlet(
            Status.CLIENT_ERROR_NOT_ACCEPTABLE);

    /**
     * This Restlet will be used to handle the request if no root resource class
     * can be found.
     */
    private Restlet errorRestletRootResourceNotFound = DEFAULT_ROOT_RESOURCE_NOT_FOUND_RESTLET;

    private Restlet errorRestletResourceNotFound = DEFAULT_RESOURCE_NOT_FOUND_RESTLET;

    /** When no Method for the give path is found */
    private Restlet errorRestletResourceMethodNotFound = DEFAULT_RESOURCE_METHOD_NOT_FOUND_RESTLET;

    private Restlet errorRestletMethodNotAllowed = DEFAULT_METHOD_NOT_ALLOWED_RESTLET;

    private Restlet errorRestletUnsupportedMediaType = DEFAULT_UNSUPPORTED_MEDIA_TYPE_RESTLET;

    private Restlet errorRestletNotAcceptable = DEFAULT_NOT_ACCEPTABLE_RESTLET;

    private Restlet errorRestletMultipleResourceMethods = DEFAULT_MULTIPLE_RESOURCE_METHODS;

    private Restlet errorRestletMultipleRootResourceClasses = DEFAULT_MULTIPLE_ROOT_RESOURCE_CLASSES;

    /**
     * Creates a new JaxRsRouter with the given Context
     * 
     * @param context
     * @param authenticator
     *                The Authenticator, must not be null. If you don't need the
     *                authentification, you can use the
     *                {@link ForbidAllAuthenticator}, the
     *                {@link AllowAllAuthenticator} or the
     *                {@link ThrowExcAuthenticator}.
     * @param loadAllRootResourceClasses
     *                if true, all accessible root resource classes are loaded.
     * @param loadAllProviders
     *                if true, all accessible providers are loaded.
     * @see Restlet#Restlet(Context)
     */
    public JaxRsRouter(Context context, Authenticator authenticator,
            boolean loadAllRootResourceClasses, boolean loadAllProviders) {
        super(context);
        this.setAuthenticator(authenticator);
        this.loadDefaultProviders();
        if (loadAllRootResourceClasses || loadAllProviders)
            JaxRsClassesLoader.loadFromClasspath(this,
                    loadAllRootResourceClasses, loadAllProviders);
    }

    private void loadDefaultProviders() throws WrappedClassLoadException,
            WrappedLoadException {
        ClassLoader classLoader = this.getClass().getClassLoader();
        JaxRsClassesLoader.loadFromPackage(classLoader, true, this, false,
                true, StringProvider.class.getPackage().getName());
    }

    /**
     * Will use the given JAX-RS root resource class.
     * 
     * @param rootResourceClass
     *                the JAX-RS root resource class to add.
     * @throws IllegalArgumentException
     * @see #detach(Class)
     * @see #getRootResourceClasses()
     */
    public void attach(Class<?> rootResourceClass)
            throws IllegalArgumentException {
        RootResourceClass newRrc = new RootResourceClass(rootResourceClass);
        PathRegExp uriTempl = newRrc.getPathRegExp();
        for (RootResourceClass rrc : this.rootResourceClasses) {
            if (rrc.getJaxRsClass().equals(rootResourceClass))
                return;// true;
            if (rrc.getPathRegExp().equals(uriTempl))
                throw new IllegalArgumentException(
                        "There is already a root resource class with path "
                                + uriTempl.getPathPattern());
        }
        rootResourceClasses.add(newRrc);
    }

    /**
     * If the automatic loading of the {@link Provider}s doesn't work, you can
     * use this method to load the providers as described in
     * {@link JaxRsClassesLoader}.
     * 
     * @param classLoader
     *                The class loader that reaches the files
     *                META-INF/services/javax.ws.rs.ext.MessageBodyWriter and
     *                META-INF/services/javax.ws.rs.ext.MessageBodyWriter with a
     *                list of the providers.
     * @param throwOnException
     * @throws IllegalArgumentException
     * @throws IOException
     * @throws ClassNotFoundException
     * @see #loadProvidersLogExc(Class)
     */
    public void loadProviders(ClassLoader classLoader, boolean throwOnException)
            throws IllegalArgumentException, IOException,
            ClassNotFoundException {
        JaxRsClassesLoader.loadProvidersFromFile(classLoader, throwOnException,
                this);
    }

    /**
     * If the automatic loading of the {@link Provider}s doesn't work, you can
     * use this method to load the providers as described in
     * {@link JaxRsClassesLoader}. Occurred Exceptions were logged.
     * 
     * @param classLoader
     *                The class loader that reaches the files
     *                META-INF/services/javax.ws.rs.ext.MessageBodyWriter and
     *                META-INF/services/javax.ws.rs.ext.MessageBodyWriter with a
     *                list of the providers.
     * @see #loadProviders(Class, boolean)
     */
    public void loadProvidersLogExc(ClassLoader classLoader) {
        try {
            JaxRsClassesLoader.loadProvidersFromFile(classLoader, false, this);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not load providers", e);
        } catch (ClassNotFoundException e) {
            getLogger().log(Level.WARNING, "Could not load providers", e);
        }
    }

    /**
     * Detaches the JAX-RS root resource class from this router.
     * 
     * @param rootResourceClass
     *                The JAX-RS root resource class to detach.
     * @return true, if the given root resource class was in the set and is
     *         removed, or false, if not.
     * @see #attach(Class)
     */
    public boolean detach(Class<?> rootResourceClass) {
        if (rootResourceClass == null)
            return false;
        Iterator<RootResourceClass> rrcIter = rootResourceClasses.iterator();
        while (rrcIter.hasNext()) {
            RootResourceClass rrc = rrcIter.next();
            if (rrc.getJaxRsClass().equals(rootResourceClass)) {
                rrcIter.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a {@link javax.ws.rs.ext.MessageBodyReader} class to this
     * JaxRsRouter. Typically you don't need this method, because it is done on
     * construction time or by {@link #addProvidersFromPackage(String...)}.
     * 
     * @param messageBodyReaderClass
     *                The {@link javax.ws.rs.ext.MessageBodyReader} class to add
     *                to the JaxRsRouter.
     * @throws JaxRsRuntimeException
     *                 if the MessageBodyReader could not be added to the
     *                 JaxRsRouter.
     */
    public void addMessageBodyReader(Class<?> messageBodyReaderClass)
            throws JaxRsRuntimeException {
        Constructor<?> constructor = RootResourceClass
                .findJaxRsConstructor(messageBodyReaderClass);
        Object provider;
        try {
            provider = RootResourceClass
                    .createInstance(constructor, null, this);
        } catch (InstantiateParameterException e) {
            // should be not possible here
            throw new JaxRsRuntimeException(
                    "Could not instantiate the root resource class", e);
        } catch (JaxRsException e) {
            String message = "MessageBodyReader could not be instantiated";
            if (e.getMessage() != null)
                message += ": " + e.getMessage();
            throw new JaxRsRuntimeException(message, e);
        } catch (RequestHandledException e) {
            throw new JaxRsRuntimeException(
                    "MessageBodyReader could not be instantiated");
        } catch (InvocationTargetException e) {
            throw new JaxRsRuntimeException(
                    "The MessageBodyReader constructor throwed an Exception", e
                            .getCause());
        }
        this.messageBodyReaders.add(new MessageBodyReader(
                (javax.ws.rs.ext.MessageBodyReader<?>) provider));
    }

    /**
     * Not yet implemented.
     * 
     * @param classLoader
     * @param throwOnExc
     * @param packageName
     * @throws WrappedLoadException
     *                 if a package could not be loaded, independent of
     *                 throwOnExc.
     * @throws WrappedClassLoadException
     *                 If a class could not be loaded and throwOnExc is true.
     */
    public void addProvidersFromPackage(ClassLoader classLoader,
            boolean throwOnExc, String... packageName)
            throws WrappedClassLoadException, WrappedLoadException {
        JaxRsClassesLoader.loadFromPackage(classLoader, throwOnExc, this,
                false, true, packageName);
    }

    /**
     * Adds a {@link javax.ws.rs.ext.MessageBodyWriter} class to this
     * JaxRsRouter. Typically you don't need this method, because it is done on
     * construction time or by {@link #addProvidersFromPackage(String...)}.
     * 
     * @param messageBodyWriterClass
     *                The {@link javax.ws.rs.ext.MessageBodyWriter} class to add
     *                to the JaxRsRouter.
     * @throws IllegalArgumentException
     *                 If no instance of the provider could created.
     */
    public void addMessageBodyWriter(Class<?> messageBodyWriterClass)
            throws IllegalArgumentException {
        Constructor<?> constructor = RootResourceClass
                .findJaxRsConstructor(messageBodyWriterClass);
        Object provider;
        try {
            provider = RootResourceClass
                    .createInstance(constructor, null, this);
        } catch (InstantiateParameterException e) {
            // should be not possible here
            throw new JaxRsRuntimeException(
                    "Could not instantiate the MessageBodyWriter", e);
        } catch (IllegalOrNoAnnotationException e) {
            throw new JaxRsRuntimeException(
                    "Could not instantiate the MessageBodyWriter", e);
        } catch (InstantiateRootRessourceException e) {
            throw new JaxRsRuntimeException(
                    "Could not instantiate the MessageBodyWriter", e);
        } catch (RequestHandledException e) {
            throw new JaxRsRuntimeException(
                    "Could not instantiate the MessageBodyWriter", e);
        } catch (InvocationTargetException e) {
            throw new JaxRsRuntimeException(
                    "Could not instantiate the MessageBodyWriter", e.getCause());
        }
        this.messageBodyWriters.add(new MessageBodyWriter(
                (javax.ws.rs.ext.MessageBodyWriter<?>) provider));
    }

    /**
     * Handles a call by invoking the next Restlet if it is available.
     * 
     * @param request
     *                The request to handle.
     * @param response
     *                The response to update.
     */
    @Override
    public void handle(Request request, Response response) {
        super.handle(request, response);
        List<Preference<MediaType>> acceptedMediaTypes = request
                .getClientInfo().getAcceptedMediaTypes();
        SortedMetadata<MediaType> accMediaTypes = new SortedMetadata<MediaType>(
                acceptedMediaTypes);
        try {
            CallContext callContext = new CallContext(request, response,
                    this.authenticator);
            try {
                ResObjAndMeth resObjAndMeth;
                try {
                    resObjAndMeth = matchingRequestToResourceMethod(
                            callContext, accMediaTypes);
                } catch (CouldNotFindMethodException e) {
                    e.errorRestlet.handle(request, response); // e.printStackTrace()
                    return;
                }
                callContext.setReadOnly();
                ResourceMethod resourceMethod = resObjAndMeth.resourceMethod;
                ResourceObject resourceObject = resObjAndMeth.resourceObject;
                invokeMethodAndHandleResult(resourceMethod, resourceObject,
                        callContext, accMediaTypes);
            } catch (WebApplicationException e) {
                handleWebAppExc(e, callContext, accMediaTypes, null);
                // Exception was handled and data were set into the Response.
            }
        } catch (RequestHandledException e) {
            // Exception was handled and data were set into the Response.
        }
    }

    /**
     * Implementation of algorithm in JSR-311-Spec, Revision 151, Version
     * 2007-12-07, Section 2.5 Matching Requests to Resource Methods.
     * 
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @param accMediaTypes
     * @return (Sub)Resource Method
     * @throws CouldNotFindMethodException
     * @throws RequestHandledException
     */
    private ResObjAndMeth matchingRequestToResourceMethod(
            CallContext callContext, SortedMetadata<MediaType> accMediaTypes)
            throws CouldNotFindMethodException, RequestHandledException {
        Request restletRequest = callContext.getRequest();
        RemainingPath u = new RemainingPath(restletRequest.getResourceRef()
                .getRemainingPart());
        RrcAndRemPath rcat = identifyRootResourceClass(u, callContext);
        ResObjAndRemPath resourceObjectAndPath = obtainObjectThatHandleRequest(
                rcat, callContext);
        MediaType givenMediaType = restletRequest.getEntity().getMediaType();
        ResObjAndMeth method = identifyMethodThatHandleRequest(
                resourceObjectAndPath, callContext, givenMediaType,
                accMediaTypes);
        return method;
    }

    /**
     * Implementation of algorithm in JSR-311-Spec, Revision 151, Version
     * 2007-12-07, Section 2.5 Matching Requests to Resource Methods, Part 1.
     * 
     * @return The identified root resource class, the remaning path after
     *         identifying and the matched template parameters; see
     *         {@link RrcAndRemPath}.
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @throws CouldNotFindMethodException
     */
    private RrcAndRemPath identifyRootResourceClass(RemainingPath u,
            CallContext callContext) throws CouldNotFindMethodException {
        // 1. Identify the root resource class:
        // (a)
        // c: Set<Class>: root resource classes
        // e: Set<RegExp>
        // Map<UriTemplateRegExp, Class> eAndCs = new HashMap();
        Collection<RootResourceClass> eAndCs = new ArrayList<RootResourceClass>();
        // (a) and (b) and (c) Filter E
        for (RootResourceClass rootResourceClass : this.rootResourceClasses) {
            // Map.Entry<UriTemplateRegExp, Class> eAndC = eAndCIter.next();
            // UriTemplateRegExp regExp = eAndC.getKey();
            // Class clazz = eAndC.getValue();
            MatchingResult matchingResult = rootResourceClass.getPathRegExp()
                    .match(u);
            if (matchingResult == null)
                continue;
            if (!Util.isEmptyOrSlash(matchingResult.getFinalMatchingGroup())
                    && !rootResourceClass.hasSubResourceMethodsOrLocators())
                continue;
            else
                eAndCs.add(rootResourceClass);
        }
        // (d)
        if (eAndCs.isEmpty())
            throw new CouldNotFindMethodException(
                    errorRestletRootResourceNotFound);
        // (e) and (f)
        RootResourceClass tClass = getFirstRrcByNumberOfLiteralCharactersAndByNumberOfCapturingGroups(eAndCs);
        // (f)
        PathRegExp rMatch = tClass.getPathRegExp();
        MatchingResult matchResult = rMatch.match(u);
        u = matchResult.getFinalCapturingGroup();
        addMrVarsToMap(matchResult, callContext);
        return new RrcAndRemPath(tClass, u);
    }

    /**
     * @param matchResult
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     */
    private void addMrVarsToMap(MatchingResult matchResult,
            CallContext callContext) {
        Map<String, String> variables = matchResult.getVariables();
        for (Map.Entry<String, String> varEntry : variables.entrySet()) {
            String key = varEntry.getKey();
            String value = varEntry.getValue();
            callContext.addTemplParamsEnc(key, value);
        }
    }

    /**
     * Implementation of algorithm in JSR-311-Spec, Revision 151, Version
     * 2007-12-07, Section 2.5, Part 2
     * 
     * @param rrcAndRemPath
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @return Resource Object
     * @throws RequestHandledException
     */
    private ResObjAndRemPath obtainObjectThatHandleRequest(
            RrcAndRemPath rrcAndRemPath, CallContext callContext)
            throws CouldNotFindMethodException, RequestHandledException {
        RemainingPath u = rrcAndRemPath.u;
        RootResourceClass resClass = rrcAndRemPath.rrc;
        PathRegExp rMatch = resClass.getPathRegExp();
        ResourceObject o;
        // LATER Do I use dynamic proxies, to inject instance variables?
        try {
            o = resClass.createInstance(callContext, this);
        } catch (InstantiateParameterException e) {
            throw new WebApplicationException(e, 404);
        } catch (Exception e) {
            throw handleCreateException(e, callContext, "createInstance",
                    "Could not create new instance of root resource class");
        }
        // Part 2
        for (;;) // (j)
        {
            // (a) If U is null or '/' go to step 3
            if (u.isEmptyOrSlash()) {
                return new ResObjAndRemPath(o, u);
            }
            // (b) Set C = class ofO,E = {}
            Collection<SubResourceMethodOrLocator> eWithMethod = new ArrayList<SubResourceMethodOrLocator>();
            // (c) and (d) Filter E: remove members do not match U or final
            // match not empty
            for (SubResourceMethodOrLocator methodOrLocator : resClass
                    .getSubResourceMethodsAndLocators()) {
                MatchingResult matchResult = methodOrLocator.getPathRegExp()
                        .match(u);
                if (matchResult == null)
                    continue;
                if (!Util.isEmptyOrSlash(matchResult.getFinalMatchingGroup()))
                    continue;
                eWithMethod.add(methodOrLocator);
            }
            // (e) If E is empty -> HTTP 404
            if (eWithMethod.isEmpty())
                throw new CouldNotFindMethodException(
                        errorRestletResourceNotFound);
            // (f) and (g) sort E, use first member of E
            SubResourceMethodOrLocator firstMeth = getFirstMethOrLocByNumberOfLiteralCharactersAndByNumberOfCapturingGroups(eWithMethod);

            rMatch = firstMeth.getPathRegExp();
            MatchingResult matchingResult = rMatch.match(u);

            addMrVarsToMap(matchingResult, callContext);

            // (h) When Method is resource method
            if (firstMeth instanceof SubResourceMethod)
                return new ResObjAndRemPath(o, u);
            // (g) and (i)
            u = matchingResult.getFinalCapturingGroup();
            SubResourceLocator subResourceLocator = (SubResourceLocator) firstMeth;
            try {
                o = subResourceLocator.createSubResource(o, callContext, this);
            } catch (InstantiateParameterException e) {
                throw new WebApplicationException(e, 404);
            } catch (Exception e) {
                throw handleCreateException(e, callContext,
                        "createSubResource",
                        "Could not create new instance of root resource class");
            }
            // (j) Go to step 2a (repeat for)
        }
    }

    /**
     * Implementation of algorithm in JSR-311-Spec, Revision 151, Version
     * 2007-12-07, Section 2.5 Matching Requests to Resource Methods, Part 3.
     * 
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @param accMediaTypes
     *                sorted by its qualities.
     * @return Resource Object and Method, that handle the request.
     * @throws RequestHandledException
     *                 for example if the method was OPTIONS, but no special
     *                 Resource Method for OPTIONS is available.
     * @throws ResourceMethodNotFoundException
     */
    private ResObjAndMeth identifyMethodThatHandleRequest(
            ResObjAndRemPath resObjAndRemPath, CallContext callContext,
            MediaType givenMediaType, SortedMetadata<MediaType> accMediaTypes)
            throws CouldNotFindMethodException, RequestHandledException {
        org.restlet.data.Method httpMethod = callContext.getRequest()
                .getMethod();
        // 3. Identify the method that will handle the request:
        // (a)
        ResourceObject resObj = resObjAndRemPath.resourceObject;
        RemainingPath u = resObjAndRemPath.u;
        // (a) 1
        Collection<ResourceMethod> resourceMethods = resObj.getResourceClass()
                .getMethodsForPath(u);
        if (resourceMethods.isEmpty())
            throw new CouldNotFindMethodException(
                    errorRestletResourceMethodNotFound);
        // (a) 2: remove methods not support the given method
        boolean alsoGet = httpMethod.equals(Method.HEAD);
        removeNotSupportedHttpMethod(resourceMethods, httpMethod, alsoGet);
        if (resourceMethods.isEmpty()) {
            if (httpMethod.equals(Method.OPTIONS)) {
                Set<Method> allowedMethods = resObj.getResourceClass()
                        .getAllowedMethods(u);
                callContext.getResponse().getAllowedMethods().addAll(
                        allowedMethods);
                throw new RequestHandledException();
            }
            throw new CouldNotFindMethodException(errorRestletMethodNotAllowed);
        }
        // (a) 3
        if (givenMediaType != null) {
            Iterator<ResourceMethod> methodIter = resourceMethods.iterator();
            while (methodIter.hasNext()) {
                ResourceMethod resourceMethod = methodIter.next();
                if (!resourceMethod.isGivenMediaTypeSupported(givenMediaType))
                    methodIter.remove();
            }
            if (resourceMethods.isEmpty())
                throw new CouldNotFindMethodException(
                        errorRestletUnsupportedMediaType);
        }
        // (a) 4
        Iterator<ResourceMethod> methodIter = resourceMethods.iterator();
        while (methodIter.hasNext()) {
            ResourceMethod resourceMethod = methodIter.next();
            if (!resourceMethod.isAcceptedMediaTypeSupported(accMediaTypes))
                methodIter.remove();
        }
        if (resourceMethods.isEmpty()) {
            // LATER zurueckgeben, welche MediaTypes unterstuetzt werden.
            throw new CouldNotFindMethodException(errorRestletNotAcceptable);
        }
        // (b) and (c)
        ResourceMethod bestResourceMethod = getBestMethod(resourceMethods,
                givenMediaType, accMediaTypes, httpMethod);
        if (bestResourceMethod == null) {
            // LATER keine Methode gefunden.
            throw new RuntimeException(
                    "Found no method, but there must be one.");
        }
        MatchingResult mr = bestResourceMethod.getPathRegExp().match(
                u);
        addMrVarsToMap(mr, callContext);
        return new ResObjAndMeth(resObj, bestResourceMethod);
    }

    /**
     * Removes the ResourceMethods doesn't support the given method
     * 
     * @param resourceMethods
     * @param httpMethod
     * @param alsoGet
     */
    private void removeNotSupportedHttpMethod(
            Collection<ResourceMethod> resourceMethods,
            org.restlet.data.Method httpMethod, boolean alsoGet) {
        Iterator<ResourceMethod> methodIter = resourceMethods.iterator();
        while (methodIter.hasNext()) {
            ResourceMethod resourceMethod = methodIter.next();
            if (!resourceMethod.isHttpMethodSupported(httpMethod, alsoGet))
                methodIter.remove();
        }
    }

    /**
     * Sort by using the media type of input data as the primary key and the
     * media type of output data as the secondary key.<br>
     * Sorting of media types follows the general rule: x/y < x/* < *<!---->/*,
     * i.e. a method that explicitly lists one of the requested media types is
     * sorted before a method that lists *<!---->/*. Quality parameter values
     * are also used such that x/y;q=1.0 < x/y;q=0.7. <br/> See JSR-311 Spec,
     * section 2.5, Part 3b+c
     * 
     * @param resourceMethods
     *                the resourceMethods that provide the required mediaType
     * @param givenMediaType
     *                The MediaType of the given entity.
     * @param accMediaTypess
     *                The accepted MediaTypes
     * @param httpMethod
     *                The HTTP method of the request.
     * @return Returns the method who best matches the given and accepted media
     *         type in the request, or null
     * @throws CouldNotFindMethodException
     */
    private ResourceMethod getBestMethod(
            Collection<ResourceMethod> resourceMethods,
            MediaType givenMediaType, SortedMetadata<MediaType> accMediaTypess,
            Method httpMethod) throws CouldNotFindMethodException {
        SortedMetadata<MediaType> givenMediaTypes;
        if (givenMediaType != null)
            givenMediaTypes = SortedMetadata.singleton(givenMediaType);
        else
            givenMediaTypes = null;
        // mms = methods that support the given MediaType
        Map<ResourceMethod, List<MediaType>> mms = findMethodSupportsMime(
                resourceMethods, ConsOrProdMime.CONSUME_MIME, givenMediaTypes);
        if (mms.isEmpty())
            return null;
        if (mms.size() == 1)
            return Util.getFirstKey(mms);
        // check for method with best ProduceMime (secondary key)
        // mms = Methods support given MediaType and requested MediaType
        mms = findMethodSupportsMime(mms.keySet(), ConsOrProdMime.PRODUCE_MIME,
                accMediaTypess);
        if (mms.isEmpty())
            return null;
        if (mms.size() == 1)
            return Util.getFirstKey(mms);
        // for (Iterable<MediaType> accMediaTypes : accMediaTypess)
        {
            for (MediaType accMediaType : accMediaTypess) {
                ResourceMethod bestMethod = null;
                for (Map.Entry<ResourceMethod, List<MediaType>> mm : mms
                        .entrySet()) {
                    for (MediaType methodMediaType : mm.getValue()) {
                        if (accMediaType.includes(methodMediaType)) {
                            ResourceMethod currentMethod = mm.getKey();
                            if (bestMethod == null) {
                                bestMethod = currentMethod;
                            } else {
                                if (httpMethod.equals(Method.HEAD)) {
                                    // special handling for HEAD
                                    if (bestMethod.getHttpMethod().equals(
                                            Method.GET)
                                            && currentMethod.getHttpMethod()
                                                    .equals(Method.HEAD)) {
                                        // ignore HEAD method
                                    } else if (bestMethod.getHttpMethod()
                                            .equals(Method.HEAD)
                                            && currentMethod.getHttpMethod()
                                                    .equals(Method.GET)) {
                                        bestMethod = currentMethod;
                                    } else {
                                        // TODO JSR311: it is not an internal
                                        // server error in
                                        // SimpleTrainTest.testGetTextAll()
                                        throwMultipleResourceMethods();
                                    }
                                } else {
                                    throwMultipleResourceMethods();
                                }
                            }
                        }
                    }
                }
                if (bestMethod != null)
                    return bestMethod;
            }
        }
        return null;
    }

    /**
     * @throws CouldNotFindMethodException
     *                 you can throw the result, if the compiler want to get
     *                 sure, that you leave the calling method.
     */
    private CouldNotFindMethodException throwMultipleResourceMethods()
            throws CouldNotFindMethodException {
        throw new CouldNotFindMethodException(
                this.errorRestletMultipleResourceMethods);
    }

    /**
     * @param resourceMethods
     * @param consumeOrPr_mime
     * @param mediaType
     * @return
     */
    private Map<ResourceMethod, List<MediaType>> findMethodSupportsMime(
            Collection<ResourceMethod> resourceMethods, ConsOrProdMime inOut,
            SortedMetadata<MediaType> mediaTypess) {
        if (mediaTypess == null || mediaTypess.isEmpty())
            return findMethodsSupportAllTypes(resourceMethods, inOut);
        Map<ResourceMethod, List<MediaType>> mms;
        mms = findMethodsSupportTypeAndSubType(resourceMethods, inOut,
                mediaTypess);
        if (mms.isEmpty()) {
            mms = findMethodsSupportType(resourceMethods, inOut, mediaTypess);
            if (mms.isEmpty())
                mms = findMethodsSupportAllTypes(resourceMethods, inOut);
        }
        return mms;
    }

    /**
     * @param resourceMethods
     * @param inOut
     * @param mediaType
     * @return Never returns null.
     */
    private Map<ResourceMethod, List<MediaType>> findMethodsSupportTypeAndSubType(
            Collection<ResourceMethod> resourceMethods, ConsOrProdMime inOut,
            SortedMetadata<MediaType> mediaTypess) {
        Map<ResourceMethod, List<MediaType>> returnMethods = new HashMap<ResourceMethod, List<MediaType>>();
        for (ResourceMethod resourceMethod : resourceMethods) {
            List<MediaType> mimes = getConsOrProdMimes(resourceMethod, inOut);
            for (MediaType resMethMediaType : mimes) {
                for (MediaType mediaType : mediaTypess)
                    if (resMethMediaType.equals(mediaType, true))
                        returnMethods.put(resourceMethod, mimes);
            }
        }
        return returnMethods;
    }

    /**
     * @param resourceMethod
     * @param inOut
     * @return
     */
    private List<MediaType> getConsOrProdMimes(ResourceMethod resourceMethod,
            ConsOrProdMime inOut) {
        if (inOut.equals(ConsOrProdMime.CONSUME_MIME))
            return resourceMethod.getConsumedMimes();
        List<MediaType> producedMimes = resourceMethod.getProducedMimes();
        if (producedMimes.isEmpty())
            return Util.createList(MediaType.ALL);
        return producedMimes;
    }

    private Map<ResourceMethod, List<MediaType>> findMethodsSupportType(
            Collection<ResourceMethod> resourceMethods, ConsOrProdMime inOut,
            SortedMetadata<MediaType> mediaTypess) {
        Map<ResourceMethod, List<MediaType>> returnMethods = new HashMap<ResourceMethod, List<MediaType>>();
        for (ResourceMethod resourceMethod : resourceMethods) {
            List<MediaType> mimes = getConsOrProdMimes(resourceMethod, inOut);
            for (MediaType resMethMediaType : mimes) {
                for (MediaType mediaType : mediaTypess) {
                    String resMethMainType = resMethMediaType.getMainType();
                    String wishedMainType = mediaType.getMainType();
                    if (resMethMainType.equals(wishedMainType))
                        returnMethods.put(resourceMethod, mimes);
                }
            }
        }
        return returnMethods;
    }

    private Map<ResourceMethod, List<MediaType>> findMethodsSupportAllTypes(
            Collection<ResourceMethod> resourceMethods, ConsOrProdMime inOut) {
        Map<ResourceMethod, List<MediaType>> returnMethods = new HashMap<ResourceMethod, List<MediaType>>();
        for (ResourceMethod resourceMethod : resourceMethods) {
            List<MediaType> mimes = getConsOrProdMimes(resourceMethod, inOut);
            for (MediaType resMethMediaType : mimes) {
                if (resMethMediaType.equals(MediaType.ALL))
                    returnMethods.put(resourceMethod, mimes);
            }
        }
        return returnMethods;
    }

    private enum ConsOrProdMime {
        /**
         * Declares that the methods etc. for the consume mime shoud be used
         */
        CONSUME_MIME,

        /**
         * Declares that the methods etc. for the produced mime shoud be used
         */
        PRODUCE_MIME
    }

    /**
     * Implementation of algorithm in JSR-311-Spec, Revision 151, Version
     * 2007-12-07, Section 2.5, Part 2f+2g
     * 
     * @param eWithMethod
     *                Collection of SubResourceMethods and SubResourceLocators
     * @return null, if the Map is null or empty
     * @throws CouldNotFindMethodException
     */
    private SubResourceMethodOrLocator getFirstMethOrLocByNumberOfLiteralCharactersAndByNumberOfCapturingGroups(
            Collection<SubResourceMethodOrLocator> eWithMethod)
            throws CouldNotFindMethodException {
        if (eWithMethod == null || eWithMethod.isEmpty())
            return null;
        Iterator<SubResourceMethodOrLocator> srmlIter = eWithMethod.iterator();
        SubResourceMethodOrLocator bestSrml = srmlIter.next();
        if (eWithMethod.size() == 1)
            return bestSrml;
        int bestSrmlChars = Integer.MIN_VALUE;
        int bestSrmlNoCaptGroups = Integer.MIN_VALUE;
        for (SubResourceMethodOrLocator srml : eWithMethod) {
            int srmlNoLitChars = srml.getPathRegExp().getNumberOfLiteralChars();
            int srmlNoCaptGroups = srml.getPathRegExp()
                    .getNumberOfCapturingGroups();
            if (srmlNoLitChars > bestSrmlChars) {
                bestSrml = srml;
                bestSrmlChars = srmlNoLitChars;
                bestSrmlNoCaptGroups = srmlNoCaptGroups;
                continue;
            }
            if (srmlNoLitChars == bestSrmlChars) {
                if (srmlNoCaptGroups > bestSrmlNoCaptGroups) {
                    bestSrml = srml;
                    bestSrmlChars = srmlNoLitChars;
                    bestSrmlNoCaptGroups = srmlNoCaptGroups;
                    continue;
                }
                if (srmlNoCaptGroups == bestSrmlNoCaptGroups) {
                    if (srml.getPathRegExp().equals(bestSrml.getPathRegExp())) {
                        // different Java methods for the same resource, but
                        // perhaps for different HTTP methods
                        continue;
                    }
                    throwMultipleResourceMethods();
                }
            }
        }
        return bestSrml;
    }

    /**
     * See JSR-311-Spec, Section 2.5 Matching Requests to Resource Methods, item
     * 1.e
     * 
     * @param rrcs
     *                Collection of root resource classes
     * @return null, if the Map is null or empty
     * @throws CouldNotFindMethodException
     */
    private RootResourceClass getFirstRrcByNumberOfLiteralCharactersAndByNumberOfCapturingGroups(
            Collection<RootResourceClass> rrcs)
            throws CouldNotFindMethodException {
        if (rrcs == null || rrcs.isEmpty())
            return null;
        Iterator<RootResourceClass> rrcIter = rrcs.iterator();
        RootResourceClass bestRrc = rrcIter.next();
        if (rrcs.size() == 1)
            return bestRrc;
        int bestRrcChars = Integer.MIN_VALUE;
        int bestRrcNoCaptGroups = Integer.MIN_VALUE;
        for (RootResourceClass rrc : rrcs) {
            int rrcNoLitChars = rrc.getPathRegExp().getNumberOfLiteralChars();
            int rrcNoCaptGroups = rrc.getPathRegExp()
                    .getNumberOfCapturingGroups();
            if (rrcNoLitChars > bestRrcChars) {
                bestRrc = rrc;
                bestRrcChars = rrcNoLitChars;
                bestRrcNoCaptGroups = rrcNoCaptGroups;
                continue;
            }
            if (rrcNoLitChars == bestRrcChars) {
                if (rrcNoCaptGroups > bestRrcNoCaptGroups) {
                    bestRrc = rrc;
                    bestRrcChars = rrcNoLitChars;
                    bestRrcNoCaptGroups = rrcNoCaptGroups;
                    continue;
                }
                if (rrcNoCaptGroups == bestRrcNoCaptGroups) {
                    // TODO JSR311: What happens, if both are equals?
                    throw new CouldNotFindMethodException(
                            this.errorRestletMultipleRootResourceClasses);
                }
            }
        }
        return bestRrc;
    }

    /**
     * Handles the given Exception.
     * 
     * @param exception
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @param methodName
     * @param logMessage
     * @throws RequestHandledException
     *                 throws this message to exit the method and indicate, that
     *                 the request was handled.
     */
    private RequestHandledException handleInvokeException(Exception exception,
            SortedMetadata<MediaType> accMediaTypes,
            ResourceMethod resourceMethod, CallContext callContext,
            String methodName, String logMessage)
            throws RequestHandledException {
        if (exception.getCause() instanceof WebApplicationException) {
            WebApplicationException webAppExc = (WebApplicationException) exception
                    .getCause();
            handleWebAppExc(webAppExc, callContext, accMediaTypes,
                    resourceMethod);
        }
        callContext.getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
        getLogger().logp(Level.WARNING, this.getClass().getName(), methodName,
                logMessage, exception.getCause());
        exception.printStackTrace();
        throw new RequestHandledException();
    }

    /**
     * Handles the given Exception.
     * 
     * @param exception
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @param methodName
     * @param logMessage
     * @param resourceMethod
     *                The invokes method.
     * @throws RequestHandledException
     *                 throws this message to exit the method and indicate, that
     *                 the request was handled.
     */
    private RequestHandledException handleCreateException(Exception exception,
            CallContext callContext, String methodName, String logMessage)
            throws RequestHandledException {
        callContext.getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
        getLogger().logp(Level.WARNING, this.getClass().getName(), methodName,
                logMessage, exception.getCause());
        exception.printStackTrace();
        throw new RequestHandledException();
    }

    /**
     * Handles the given {@link WebApplicationException}.
     * 
     * @param webAppExc
     *                The {@link WebApplicationException} to handle
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @param accMediaTypes
     *                the accepted MediaType, see {@link SortedMetadata}.
     * @throws RequestHandledException
     *                 throws this message to exit the method and indicate, that
     *                 the request was handled.
     */
    private RequestHandledException handleWebAppExc(
            WebApplicationException webAppExc, CallContext callContext,
            SortedMetadata<MediaType> accMediaTypes,
            ResourceMethod resourceMethod) throws RequestHandledException {
        // the message of the Exception is not used in the
        // WebApplicationException
        jaxRsRespToRestletResp(webAppExc.getResponse(), callContext,
                resourceMethod, accMediaTypes);
        // MediaType rausfinden
        throw new RequestHandledException();
    }

    /**
     * @param resourceMethod
     * @param resourceObject
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     */
    private void invokeMethodAndHandleResult(ResourceMethod resourceMethod,
            ResourceObject resourceObject, CallContext callContext,
            SortedMetadata<MediaType> accMediaTypes)
            throws RequestHandledException {
        Object result;
        try {
            result = resourceMethod.invoke(resourceObject, callContext, this);
        } catch (InstantiateParameterException e) {
            throw new WebApplicationException(e, 404);
        } catch (InvocationTargetException ite) {
            // TODO wenn RuntimeException, dann weitergeben.
            throw handleInvokeException(ite, accMediaTypes, resourceMethod,
                    callContext, "invoke", "Exception in resource method");
        } catch (RequestHandledException e) {
            throw e;
        } catch (Exception e) {
            throw handleInvokeException(e, accMediaTypes, resourceMethod,
                    callContext, "invoke", "Can not invoke the resource method");
        }
        Response restletResponse = callContext.getResponse();
        if (result == null) { // no representation
            restletResponse.setStatus(Status.SUCCESS_NO_CONTENT);
            restletResponse.setEntity(null);
            return;
        } else {
            restletResponse.setStatus(Status.SUCCESS_OK);
            if (result instanceof javax.ws.rs.core.Response) {
                jaxRsRespToRestletResp((javax.ws.rs.core.Response) result,
                        callContext, resourceMethod, accMediaTypes);
                // } else if(result instanceof URI) { // perhaps 201 or 303
            } else if (result instanceof javax.ws.rs.core.Response.ResponseBuilder) {
                javax.ws.rs.core.Response jaxRsResponse = ((javax.ws.rs.core.Response.ResponseBuilder) result)
                        .build();
                jaxRsRespToRestletResp(jaxRsResponse, callContext,
                        resourceMethod, accMediaTypes);
            } else {
                Representation entity = convertToRepresentation(result,
                        resourceMethod, accMediaTypes, callContext, null);
                restletResponse.setEntity(entity);
                // throw new NotYetImplementedException();
                // LATER perhaps another default as option (email 2008-01-29)
            }
        }
    }

    private void jaxRsRespToRestletResp(
            javax.ws.rs.core.Response jaxRsResponse, CallContext callContext,
            ResourceMethod resourceMethod,
            SortedMetadata<MediaType> accMediaTypes)
            throws RequestHandledException {
        Response restletResponse = callContext.getResponse();
        restletResponse.setStatus(Status.valueOf(jaxRsResponse.getStatus()));
        Object mediaTypeStr = jaxRsResponse.getMetadata().getFirst(
                HttpHeaders.CONTENT_TYPE);
        MediaType respMediaType = null;
        if (mediaTypeStr != null)
            respMediaType = MediaType.valueOf(mediaTypeStr.toString());
        restletResponse.setEntity(convertToRepresentation(jaxRsResponse
                .getEntity(), resourceMethod, accMediaTypes, callContext,
                respMediaType));
        Util.copyResponseHeaders(jaxRsResponse.getMetadata(), restletResponse,
                getLogger());
    }

    /**
     * 
     * @param entity
     * @param resourceMethod
     * @param accMediaTypes
     * @param callContext
     *                Contains the encoded template Parameters, that are read
     *                from the called URI, the Restlet {@link Request} and the
     *                Restlet {@link Response}.
     * @param responseMediaType
     *                The MediaType of the JAX-RS response. May be null.
     * @return
     * @throws RequestHandledException
     */
    private Representation convertToRepresentation(Object entity,
            ResourceMethod resourceMethod,
            SortedMetadata<MediaType> accMediaTypes, CallContext callContext,
            MediaType responseMediaType) throws RequestHandledException {
        if (entity instanceof Representation)
            return (Representation) entity;
        if (entity == null)
            return null;
        Class<? extends Object> entityClass = entity.getClass();
        MessageBodyWriterSet mbws = this.messageBodyWriters.subSet(entityClass);
        List<MediaType> possMediaTypes;
        if (responseMediaType != null)
            possMediaTypes = Collections.singletonList(responseMediaType);
        else
            possMediaTypes = determineMediaType16(resourceMethod, mbws,
                    accMediaTypes, callContext.getResponse());
        mbws = mbws.subSet(possMediaTypes);
        MessageBodyWriter mbw = mbws.getBest(accMediaTypes);
        if (mbw == null)
            throw handleWebAppExc(new WebApplicationException(406),
                    callContext, accMediaTypes, resourceMethod);
        MediaType mediaType;
        if (responseMediaType != null)
            mediaType = responseMediaType;
        else
            mediaType = determineMediaType79(possMediaTypes, callContext
                    .getResponse());
        MultivaluedMap<String, Object> httpResponseHeaders = null;
        // TODO Http-ResponseHeaders
        return new JaxRsOutputRepresentation(entity, mediaType, mbw,
                httpResponseHeaders);
    }

    /**
     * Determines the MediaType for a response. See JAX-RS-Spec, Section 2.6
     * "Determining the MediaType of Responses", Parts 1-6
     * 
     * @param resourceMethod
     *                The ResourceMethod that created the entity.
     * @param mbwsForEntityClass
     *                {@link MessageBodyWriter}s, that support the entity
     *                class.
     * @param accMediaTypes
     *                see {@link SortedMetadata}
     * @param restletResponse
     *                The Restlet {@link Response}; needed for a not acceptable
     *                return.
     * @return
     * @throws RequestHandledException
     */
    private List<MediaType> determineMediaType16(ResourceMethod resourceMethod,
            MessageBodyWriterSet mbwsForEntityClass,
            SortedMetadata<MediaType> accMediaTypes, Response restletResponse)
            throws RequestHandledException {
        // 1. Gather the set of producible media types P:
        // (a) + (b)
        List<MediaType> p = resourceMethod.getProducedMimes();
        // 1. (c)
        if (p.isEmpty()) {
            p = new ArrayList<MediaType>();
            for (MessageBodyWriter messageBodyWriter : mbwsForEntityClass)
                p.addAll(messageBodyWriter.getProducedMimes());
        }
        // 2.
        if (p.isEmpty())
            return Collections.singletonList(MediaType.ALL);
        // 3. Obtain the acceptable media types A. If A = {}, set A = {'*/*'}
        if (accMediaTypes.isEmpty())
            accMediaTypes = SortedMetadata.getMediaTypeAll();
        // 4. Sort P and A: a is already sorted.
        p = Util.sortByConcreteness(p);
        // 5.
        List<MediaType> m = new ArrayList<MediaType>();
        for (MediaType prod : p)
            for (MediaType acc : accMediaTypes)
                if (Util.isCompatible(prod, acc))
                    m.add(Util.mostSpecific(prod, acc));
        // 6.
        if (m.isEmpty())
            throw throwNotAcceptable(restletResponse);
        return m;
    }

    /**
     * Determines the MediaType for a response. See JAX-RS-Spec, Section 2.6
     * "Determining the MediaType of Responses", Part 7-9
     * 
     * @param m
     *                the possible {@link MediaType}s.
     * @param restletResponse
     *                The Restlet {@link Response}; needed for a not acceptable
     *                return.
     * @return the determined {@link MediaType}
     * @throws RequestHandledException
     */
    private MediaType determineMediaType79(List<MediaType> m,
            Response restletResponse) throws RequestHandledException {
        // 7.
        for (MediaType mediaType : m)
            if (Util.isConcrete(mediaType))
                return mediaType;
        // 8.
        if (m.contains(MediaType.ALL) || m.contains(MediaType.APPLICATION_ALL))
            return MediaType.APPLICATION_OCTET_STREAM;
        // 9.
        throw throwNotAcceptable(restletResponse);
    }

    private RequestHandledException throwNotAcceptable(Response restletResponse)
            throws RequestHandledException {
        restletResponse.setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
        throw new RequestHandledException();
    }

    /**
     * Restlet, is used on HTTP-Error 404, when no Root Resource class could be
     * found.
     * 
     * @param rootResourceNotFoundRestlet
     *                The Restlet to use when no root resource class could be
     *                found. This Restlet must return status 404.
     * @throws IllegalArgumentException
     *                 If the Restlet is null.
     */
    public void setErrorRestletRootResourceNotFound(
            Restlet rootResourceNotFoundRestlet)
            throws IllegalArgumentException {
        if (rootResourceNotFoundRestlet == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletRootResourceNotFound = rootResourceNotFoundRestlet;
    }

    /**
     * @return Returns the Restlet, that is actually if no Root Resource class
     *         could be found.
     */
    public Restlet getErrorRestletRootResourceNotFound() {
        return this.errorRestletRootResourceNotFound;
    }

    /**
     * Restlet, is used on HTTP-Error 404, when no Resource class could be
     * found.
     * 
     * @param resourceNotFoundRestlet
     *                The Restlet to use when no resource class could be found.
     *                This Restlet must return status 404.
     * @throws IllegalArgumentException
     *                 If the Restlet is null.
     */
    public void setErrorRestletResourceNotFound(Restlet resourceNotFoundRestlet)
            throws IllegalArgumentException {
        if (resourceNotFoundRestlet == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletResourceNotFound = resourceNotFoundRestlet;
    }

    /**
     * @return Returns the Restlet, that is actually if no resource class could
     *         be found.
     */
    public Restlet getErrorRestletResourceNotFound() {
        return this.errorRestletResourceNotFound;
    }

    /**
     * Sets the Restlet that handles the request if no Resource class could be
     * found.
     * 
     * @param resourceMethodNotFoundRestlet
     *                The Restlet to use when no resource class could be found.
     *                This Restlet must return status 404.
     * @throws IllegalArgumentException
     *                 If the given Restlet is null.
     * @see #DEFAULT_RESOURCE_METHOD_NOT_FOUND_RESTLET
     */
    public void setErrorRestletResourceMethodNotFound(
            Restlet resourceMethodNotFoundRestlet)
            throws IllegalArgumentException {
        if (resourceMethodNotFoundRestlet == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletResourceMethodNotFound = resourceMethodNotFoundRestlet;
    }

    /**
     * @return Returns the Restlet, is used on HTTP-Error 404, when no Resource
     *         class could be found.
     */
    public Restlet getErrorRestletResourceMethodNotFound() {
        return this.errorRestletResourceMethodNotFound;
    }

    /**
     * @return Returns the Restlet, that is actually if no resource method could
     *         be found.
     */
    public Restlet getErrorRestletMethodNotAllowed() {
        return errorRestletMethodNotAllowed;
    }

    /**
     * Set the Restlet to be used if the method is not allowed for the resource.
     * It must return status 405.
     * 
     * @param errorRestletMethodNotAllowed
     *                The Restlet to use.
     * @throws IllegalArgumentException
     *                 If the given restlet is null.
     */
    public void setErrorRestletMethodNotAllowed(
            Restlet errorRestletMethodNotAllowed)
            throws IllegalArgumentException {
        if (errorRestletMethodNotAllowed == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletMethodNotAllowed = errorRestletMethodNotAllowed;
    }

    /**
     * @return Returns the Restlet that handles the request, if the method is
     *         not allowed on the resource.
     */
    public Restlet getErrorRestletUnsupportedMediaType() {
        return errorRestletUnsupportedMediaType;
    }

    /**
     * Sets the Restlet that handles the request if the given media type is not
     * supported.
     * 
     * @param errorRestletUnsupportedMediaType
     *                The Restlet to use.
     * @throws IllegalArgumentException
     *                 If the given restlet is null.
     */
    public void setErrorRestletUnsupportedMediaType(
            Restlet errorRestletUnsupportedMediaType)
            throws IllegalArgumentException {
        if (errorRestletUnsupportedMediaType == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletUnsupportedMediaType = errorRestletUnsupportedMediaType;
    }

    /**
     * @return Returns the Restlet that hanndles the request if the accepted
     *         media type is not supported.
     */
    public Restlet getErrorRestletNotAcceptable() {
        return errorRestletNotAcceptable;
    }

    /**
     * Sets the Restlet that should handle the request, if the accpeted media
     * type is not supported. Must return status 406.
     * 
     * @param errorRestletNotAcceptable
     *                The Restlet to use
     * @throws IllegalArgumentException
     *                 If the given restlet is null.
     */
    public void setErrorRestletNotAcceptable(Restlet errorRestletNotAcceptable)
            throws IllegalArgumentException {
        if (errorRestletNotAcceptable == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletNotAcceptable = errorRestletNotAcceptable;
    }

    /**
     * @return Returns the Restlet that handles the request if multiple resource
     *         methods for a request were found.
     */
    public Restlet getErrorRestletMultipleResourceMethods() {
        return errorRestletMultipleResourceMethods;
    }

    /**
     * 
     * @param errorRestletMultipleResourceMethods
     * @throws IllegalArgumentException
     *                 If the given restlet is null.
     */
    public void setErrorRestletMultipleResourceMethods(
            Restlet errorRestletMultipleResourceMethods)
            throws IllegalArgumentException {
        if (errorRestletMultipleResourceMethods == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletMultipleResourceMethods = errorRestletMultipleResourceMethods;
    }

    /**
     * @return Returns the request if multiple root resource classes were found.
     */
    public Restlet getErrorRestletMultipleRootResourceClasses() {
        return errorRestletMultipleRootResourceClasses;
    }

    /**
     * 
     * @param errorRestletMultipleRootResourceClasses
     * @throws IllegalArgumentException
     *                 If the given restlet is null.
     */
    public void setErrorRestletMultipleRootResourceClasses(
            Restlet errorRestletMultipleRootResourceClasses)
            throws IllegalArgumentException {
        if (errorRestletMultipleRootResourceClasses == null)
            throw new IllegalArgumentException(
                    "The Error Restlet must not be null");
        this.errorRestletMultipleRootResourceClasses = errorRestletMultipleRootResourceClasses;
    }

    /**
     * Returns a set with the attached root resource classes.
     * 
     * @return
     */
    public Set<Class<?>> getRootResourceClasses() {
        Set<Class<?>> rrcs = new HashSet<Class<?>>();
        for (RootResourceClass rootResourceClass : this.rootResourceClasses)
            rrcs.add(rootResourceClass.getJaxRsClass());
        return Collections.unmodifiableSet(rrcs);
    }

    /**
     * Structure to return the identiied {@link RootResourceClass}, the
     * remaining path after identifying and the matched template parameters.
     * 
     * @author Stephan Koops
     */
    class RrcAndRemPath {
        private RootResourceClass rrc;

        private RemainingPath u;

        RrcAndRemPath(RootResourceClass rrc, RemainingPath u) {
            this.rrc = rrc;
            this.u = u;
        }
    }

    /**
     * Structure to return the obtained {@link ResourceObject}, the remaining
     * path after identifying the object and all matched template parameters.
     * 
     * @author Stephan Koops
     */
    class ResObjAndRemPath {

        private ResourceObject resourceObject;

        private RemainingPath u;

        ResObjAndRemPath(ResourceObject resourceObject, RemainingPath u) {
            this.resourceObject = resourceObject;
            this.u = u;
        }
    }

    /**
     * Structure to return the obtained {@link ResourceObject}, the
     * {@link ResourceMethod} identifying it and all matched template
     * parameters.
     * 
     * @author Stephan Koops
     */
    class ResObjAndMeth {

        private ResourceObject resourceObject;

        private ResourceMethod resourceMethod;

        ResObjAndMeth(ResourceObject resourceObject,
                ResourceMethod resourceMethod) {
            this.resourceObject = resourceObject;
            this.resourceMethod = resourceMethod;
        }
    }

    /**
     * This exception is thrown, when the algorithm "Matching Requests to
     * Resource Methods" in Section 2.5 of JSR-311-Spec could not find a method.
     * 
     * @author Stephan Koops
     */
    private class CouldNotFindMethodException extends Exception {
        private static final long serialVersionUID = -8436314060905405146L;

        private Restlet errorRestlet;

        CouldNotFindMethodException(Restlet errorRestlet) {
            this.errorRestlet = errorRestlet;
        }
    }

    /**
     * Instances of this class have a given status they return, when
     * {@link Restlet#handle(Request, Response)} is called.
     * 
     * @author Stephan Koops
     */
    private static class ReturnStatusRestlet extends Restlet {
        private Status status;

        ReturnStatusRestlet(Status status) {
            this.status = status;
        }

        @Override
        public void handle(Request request, Response response) {
            super.handle(request, response);
            response.setStatus(status);
        }
    }

    /**
     * @return the authenticator
     */
    public Authenticator getAuthenticator() {
        return authenticator;
    }

    /**
     * @param authenticator
     *                the authenticator to set
     */
    public void setAuthenticator(Authenticator authenticator) {
        if (authenticator == null)
            throw new IllegalArgumentException(
                    "The authenticator must nit be null. You can use the "
                            + AllowAllAuthenticator.class.getName()
                            + " or the "
                            + ForbidAllAuthenticator.class.getName());
        this.authenticator = authenticator;
    }

    /**
     * for internal use only
     * 
     * @see org.restlet.ext.jaxrs.wrappers.HiddenJaxRsRouter#getMessageBodyReaders()
     */
    @Deprecated
    public MessageBodyReaderSet getMessageBodyReaders() {
        return this.messageBodyReaders;
    }

    /**
     * for internal use only
     * 
     * @see org.restlet.ext.jaxrs.wrappers.HiddenJaxRsRouter#getMessageBodyWriters()
     */
    @Deprecated
    public MessageBodyWriterSet getMessageBodyWriters() {
        return this.messageBodyWriters;
    }
}