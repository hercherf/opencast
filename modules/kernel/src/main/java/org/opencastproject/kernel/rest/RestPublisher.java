/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.kernel.rest;

import org.opencastproject.rest.RestConstants;
import org.opencastproject.rest.StaticResource;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.systems.OpencastConstants;
import org.opencastproject.util.NotFoundException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.binding.BindingFactoryManager;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSBindingFactory;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.ext.ResourceComparator;
import org.apache.cxf.jaxrs.impl.UriInfoImpl;
import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.jaxrs.model.OperationResourceInfo;
import org.apache.cxf.jaxrs.provider.json.JSONProvider;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.servlet.CXFNonSpringServlet;
import org.apache.http.HttpStatus;
import org.codehaus.jettison.mapped.Configuration;
import org.codehaus.jettison.mapped.MappedNamespaceConvention;
import org.codehaus.jettison.mapped.MappedXMLStreamWriter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Listens for JAX-RS annotated services and publishes them to the global URL space using a single shared HttpContext.
 */
@Component(
    immediate = true,
    service = RestPublisher.class,
    property = {
        "service.description=Opencast REST Endpoint Publisher"
    }
)
public class RestPublisher implements RestConstants {

  /** The logger **/
  protected static final Logger logger = LoggerFactory.getLogger(RestPublisher.class);

  /** The rest publisher looks for any non-servlet with the 'opencast.service.path' property */
  public static final String JAX_RS_SERVICE_FILTER = "(&(!(objectClass=javax.servlet.Servlet))(" + SERVICE_PATH_PROPERTY
          + "=*))";
  //public static final String JAX_RS_SERVICE_FILTER = "(&(!(objectClass=javax.servlet.Servlet))(opencast.jaxrs.resource=true))";

  /** A map that sets default xml namespaces in {@link XMLStreamWriter}s */
  protected static final ConcurrentHashMap<String, String> NAMESPACE_MAP;

  protected List<Object> providers = null;

  static {
    NAMESPACE_MAP = new ConcurrentHashMap<>();
    NAMESPACE_MAP.put("http://www.w3.org/2001/XMLSchema-instance", "");
  }

  /** The rest publisher's OSGI declarative services component context */
  protected ComponentContext componentContext;

  /** A service tracker that monitors JAX-RS annotated services, (un)publishing servlets as they (dis)appear */
  protected ServiceTracker<Object, Object> jaxRsTracker = null;

  /**
   * A bundle tracker that registers StaticResource servlets for bundles with the right headers.
   */
  protected BundleTracker<Object> bundleTracker = null;

  /** The base URL for this server */
  protected String baseServerUri;

  /** Holds references to servlets that this class publishes, so they can be unpublished later */
  protected Map<String, ServiceRegistration<?>> servletRegistrationMap;

  /** The JAX-RS Server */
  private Server server;

  /** The CXF Bus */
  private Bus bus;

  private ServiceRegistration<Bus> busServiceRegistration;

  /** The List of JAX-RS resources */
  private final List<Object> serviceBeans = new CopyOnWriteArrayList<>();

  /** A token to store in the miss cache */
  private final Object nullToken = new Object();

  private final CacheLoader<Class<?>, Object> servicePathLoader = new CacheLoader<Class<?>, Object>() {
    @Override
    public Object load(Class<?> clazz) {
      ServiceReference<?> ref = componentContext.getBundleContext().getServiceReference(clazz.getName());
      if (ref == null) {
        logger.warn("No service reference found for class {}", clazz.getName());
        return nullToken;
      }
      String servicePath = (String) ref.getProperty(SERVICE_PATH_PROPERTY);
      return StringUtils.isBlank(servicePath) ? nullToken : servicePath;
    }
  };

  private final LoadingCache<Class<?>, Object> servicePathCache = CacheBuilder.newBuilder()
          .expireAfterWrite(5, TimeUnit.MINUTES).build(servicePathLoader);

  /** Activates this rest publisher */
  @SuppressWarnings("unchecked")
  @Activate
  protected void activate(ComponentContext componentContext) {
    logger.debug("activate()");
    baseServerUri = componentContext.getBundleContext().getProperty(OpencastConstants.SERVER_URL_PROPERTY);
    this.componentContext = componentContext;
    servletRegistrationMap = new ConcurrentHashMap<>();
    providers = new ArrayList<>();

    JSONProvider jsonProvider = new OpencastJSONProvider();
    jsonProvider.setIgnoreNamespaces(true);
    jsonProvider.setNamespaceMap(NAMESPACE_MAP);
    providers.add(jsonProvider);

    providers.add(new ExceptionMapper<NotFoundException>() {
      @Override
      public Response toResponse(NotFoundException e) {
        return Response
                .status(HttpStatus.SC_NOT_FOUND)
                .entity("The resource you requested does not exist.")
                .type(MediaType.TEXT_PLAIN)
                .build();
      }
    });
    providers.add(new ExceptionMapper<UnauthorizedException>() {
      @Override
      public Response toResponse(UnauthorizedException e) {
        return Response
                .status(HttpStatus.SC_UNAUTHORIZED)
                .entity("unauthorized")
                .type(MediaType.TEXT_PLAIN)
                .build();
      }
    });

    this.bus = BusFactory.getDefaultBus();

    busServiceRegistration = componentContext.getBundleContext().registerService(Bus.class, bus, new Hashtable<>());

    try {
      jaxRsTracker = new JaxRsServiceTracker();
      bundleTracker = new StaticResourceBundleTracker(componentContext.getBundleContext());
    } catch (InvalidSyntaxException e) {
      throw new IllegalStateException(e);
    }
    jaxRsTracker.open();
    bundleTracker.open();
  }

  /**
   * Deactivates the rest publisher
   */
  @Deactivate
  protected void deactivate() {
    logger.debug("deactivate()");
    jaxRsTracker.close();
    bundleTracker.close();
    busServiceRegistration.unregister();
  }

  @Reference(policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.OPTIONAL)
  public void bindHttpService(HttpService httpService) {
    logger.debug("HttpService registered");
    rewire();
  }

  public void unbindHttpService(HttpService httpService) {
    logger.debug("HttpService unregistered");
  }

  protected class OsgiCxfEndpointComparator implements ResourceComparator {

    @Override
    public int compare(OperationResourceInfo oper1, OperationResourceInfo oper2, Message message) {
      return compareByServiceClass(oper1.getClassResourceInfo().getServiceClass(),
              oper2.getClassResourceInfo().getServiceClass(), message);
    }

    @Override
    public int compare(ClassResourceInfo cri1, ClassResourceInfo cri2, Message message) {
      return compareByServiceClass(cri1.getServiceClass(), cri2.getServiceClass(), message);
    }

    private int compareByServiceClass(Class<?> clazz1, Class<?> clazz2, Message message) {
      if (clazz1.equals(clazz2))
        return 0;

      UriInfoImpl uriInfo = new UriInfoImpl(message);
      String path = uriInfo.getBaseUri().getPath();
      path = StringUtils.removeEnd(path, "/");
      if (StringUtils.isBlank(path))
        return 0;

      Object servicePath1 = servicePathCache.getUnchecked(clazz1);
      Object servicePath2 = servicePathCache.getUnchecked(clazz2);

      if (servicePath1 != nullToken && path.equals(servicePath1)) {
        return -1;
      } else if (servicePath2 != nullToken && path.equals(servicePath2)) {
        return 1;
      } else if (servicePath1 != nullToken && servicePath2 != nullToken) {
        return servicePath1.toString().compareTo(servicePath2.toString());
      } else {
        return 0;
      }
    }
  }

  /**
   * Creates a REST endpoint for the JAX-RS annotated service.
   *
   * @param ref
   *          the osgi service reference
   * @param service
   *          The service itself
   */
  protected void createEndpoint(ServiceReference<?> ref, Object service) {
    String serviceType = (String) ref.getProperty(SERVICE_TYPE_PROPERTY);
    String servicePath = (String) ref.getProperty(SERVICE_PATH_PROPERTY);
    boolean servicePublishFlag = ref.getProperty(SERVICE_PUBLISH_PROPERTY) == null
            || Boolean.parseBoolean(ref.getProperty(SERVICE_PUBLISH_PROPERTY).toString());
    boolean jobProducer = ref.getProperty(SERVICE_JOBPRODUCER_PROPERTY) != null
            && Boolean.parseBoolean(ref.getProperty(SERVICE_JOBPRODUCER_PROPERTY).toString());

    ServiceRegistration<?> reg = servletRegistrationMap.get(servicePath);
    if (reg != null) {
      logger.debug("Rest endpoint {} is still registred, skip registering again", servicePath);
      return;
    }

    RestServlet cxf = new RestServlet();
    cxf.setBus(bus);
    try {
      Dictionary<String, Object> props = new Hashtable<>();

      props.put(SERVICE_TYPE_PROPERTY, serviceType);
      props.put(SERVICE_PATH_PROPERTY, servicePath);
      props.put(SERVICE_PUBLISH_PROPERTY, servicePublishFlag);
      props.put(SERVICE_JOBPRODUCER_PROPERTY, jobProducer);
      props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=" + RestConstants.HTTP_CONTEXT_ID + ")");
      props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, servicePath);
      props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, servicePath + "/*");

      reg = componentContext.getBundleContext().registerService(Servlet.class.getName(), cxf, props);
    } catch (Exception e) {
      logger.info("Problem registering REST endpoint {} : {}", servicePath, e.getMessage());
      return;
    }
    servletRegistrationMap.put(servicePath, reg);

    serviceBeans.add(service);

    rewire();

    logger.info("Registered REST endpoint at " + servicePath);
    if (service instanceof RestEndpoint) {
      ((RestEndpoint) service).endpointPublished();
    }
  }

  /**
   * Removes an endpoint
   *
   * @param alias
   *          The URL space to reclaim
   * @param service
   *          The service reference
   */
  protected void destroyEndpoint(String alias, Object service) {
    ServiceRegistration<?> reg = servletRegistrationMap.remove(alias);
    serviceBeans.remove(service);
    if (reg != null) {
      reg.unregister();
    }

    rewire();
  }

  private synchronized void rewire() {

    if (serviceBeans.isEmpty()) {
      logger.info("No resource classes skip JAX-RS server recreation");
      return;
    }

    // Set up cxf
    JAXRSServerFactoryBean sf = new JAXRSServerFactoryBean();
    sf.setBus(bus);
    sf.setProviders(providers);

    // Set the service class
    sf.setResourceComparator(new OsgiCxfEndpointComparator());

    sf.setAddress("/");

    sf.setProperties(new HashMap<>());
    BindingFactoryManager manager = sf.getBus().getExtension(BindingFactoryManager.class);
    JAXRSBindingFactory factory = new JAXRSBindingFactory();
    factory.setBus(bus);
    manager.registerBindingFactory(JAXRSBindingFactory.JAXRS_BINDING_ID, factory);

    if (server != null) {
      logger.debug("Destroying JAX-RS server");
      server.stop();
      server.destroy();
    }

    sf.setServiceBeans(serviceBeans);
    server = sf.create();
  }

  /**
   * Extends the CXF JSONProvider for the grand purpose of removing '@' symbols from json and padded jsonp.
   */
  protected static class OpencastJSONProvider<T> extends JSONProvider<T> {
    private static final Charset UTF8 = Charset.forName("utf-8");

    /**
     * {@inheritDoc}
     */
    @Override
    protected XMLStreamWriter createWriter(Object actualObject, Class<?> actualClass, Type genericType, String enc,
            OutputStream os, boolean isCollection) throws Exception {
      Configuration c = new Configuration(NAMESPACE_MAP);
      c.setSupressAtAttributes(true);
      MappedNamespaceConvention convention = new MappedNamespaceConvention(c);
      return new MappedXMLStreamWriter(convention, new OutputStreamWriter(os, UTF8)) {
        @Override
        public void writeStartElement(String prefix, String local, String uri) throws XMLStreamException {
          super.writeStartElement("", local, "");
        }

        @Override
        public void writeStartElement(String uri, String local) throws XMLStreamException {
          super.writeStartElement("", local, "");
        }

        @Override
        public void setPrefix(String pfx, String uri) throws XMLStreamException {
        }

        @Override
        public void setDefaultNamespace(String uri) throws XMLStreamException {
        }
      };
    }
  }

  /**
   * A custom ServiceTracker that published JAX-RS annotated services with the
   * {@link RestPublisher#SERVICE_PATH_PROPERTY} property set to some non-null value.
   */
  public class JaxRsServiceTracker extends ServiceTracker<Object, Object> {

    JaxRsServiceTracker() throws InvalidSyntaxException {
      super(componentContext.getBundleContext(),
              componentContext.getBundleContext().createFilter(JAX_RS_SERVICE_FILTER), null);
    }

    @Override
    public void removedService(ServiceReference<Object> reference, Object service) {
      String servicePath = (String) reference.getProperty(SERVICE_PATH_PROPERTY);
      destroyEndpoint(servicePath, service);
      super.removedService(reference, service);
    }

    @Override
    public Object addingService(ServiceReference<Object> reference) {
      Object service = componentContext.getBundleContext().getService(reference);
      if (service == null) {
        logger.info("JAX-RS service {} has not been instantiated yet, or has already been unregistered. Skipping "
                + "endpoint creation.", reference);
      } else {
        Path pathAnnotation = service.getClass().getAnnotation(Path.class);
        if (pathAnnotation == null) {
          logger.warn(
                  "{} was registered with '{}={}', but the service is not annotated with the JAX-RS "
                          + "@Path annotation",
                  service, SERVICE_PATH_PROPERTY, reference.getProperty(SERVICE_PATH_PROPERTY));
        } else {
          createEndpoint(reference, service);
        }
      }
      return super.addingService(reference);
    }
  }

  /**
   * A classloader that delegates to an OSGI bundle for loading resources.
   */
  class StaticResourceClassLoader extends ClassLoader {
    private Bundle bundle = null;

    StaticResourceClassLoader(Bundle bundle) {
      super();
      this.bundle = bundle;
    }

    @Override
    public URL getResource(String name) {
      URL url = bundle.getResource(name);
      logger.debug("{} found resource {} from name {}", this, url, name);
      return url;
    }
  }

  /**
   * Tracks bundles containing static resources to be exposed via HTTP URLs.
   */
  class StaticResourceBundleTracker extends BundleTracker<Object> {

    private HashMap<Bundle, ServiceRegistration> servlets = new HashMap<>();

    /**
     * Creates a new StaticResourceBundleTracker.
     *
     * @param context
     *          the bundle context
     */
    StaticResourceBundleTracker(BundleContext context) {
      super(context, Bundle.ACTIVE, null);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.osgi.util.tracker.BundleTracker#addingBundle(org.osgi.framework.Bundle, org.osgi.framework.BundleEvent)
     */
    @Override
    public Object addingBundle(Bundle bundle, BundleEvent event) {
      String classpath = bundle.getHeaders().get(RestConstants.HTTP_CLASSPATH);
      String alias = bundle.getHeaders().get(RestConstants.HTTP_ALIAS);
      String welcomeFile = bundle.getHeaders().get(RestConstants.HTTP_WELCOME);
      // Always false if not set to true
      boolean spaRedirect = Boolean.parseBoolean(bundle.getHeaders().get(RestConstants.HTTP_SPA_REDIRECT));

      if (classpath != null && alias != null) {
        Dictionary<String, String> props = new Hashtable<>();

        props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=" + RestConstants.HTTP_CONTEXT_ID + ")");
        props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, alias);
        if ("/".equals(alias)) {
          props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, alias);
        } else {
          props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, alias + "/*");
        }
        StaticResource servlet = new StaticResource(new StaticResourceClassLoader(bundle), classpath, alias,
                welcomeFile, spaRedirect);

        // We use the newly added bundle's context to register this service, so when that bundle shuts down, it brings
        // down this servlet with it
        logger.info("Registering servlet with alias {}", alias + "/*");

        ServiceRegistration<?> serviceRegistration = componentContext.getBundleContext()
                .registerService(Servlet.class.getName(), servlet, props);
        servlets.put(bundle, serviceRegistration);
      }

      return super.addingBundle(bundle, event);
    }

    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, Object object) {
      String classpath = bundle.getHeaders().get(RestConstants.HTTP_CLASSPATH);
      String alias = bundle.getHeaders().get(RestConstants.HTTP_ALIAS);
      if (classpath != null && alias != null) {
        ServiceRegistration serviceRegistration = servlets.get(bundle);
        if (serviceRegistration != null) {
          serviceRegistration.unregister();
          servlets.remove(bundle);
        }
      }

      super.removedBundle(bundle, event, object);
    }
  }

  /**
   * An HttpServlet that uses a JAX-RS service to handle requests.
   */
  public class RestServlet extends CXFNonSpringServlet {
    /** Serialization UID */
    private static final long serialVersionUID = -8963338160276371426L;

    /**
     * Default constructor needed by Jetty
     */
    public RestServlet() {

    }

    @Override
    public void destroyBus() {
      // Do not destroy bus if servlet gets unregistered
    }

    @Override
    protected void handleRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException {
      if (request.getRequestURI().endsWith("/docs")) {
        try {
          response.sendRedirect("/docs.html?path=" + request.getServletPath());
        } catch (IOException e) {
          logger.error("Unable to redirect to rest docs:", e);
        }
      } else {
        super.handleRequest(request, response);
      }
    }

  }

}
