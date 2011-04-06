/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.modcluster;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.util.StringManager;
import org.jboss.logging.Logger;
import org.jboss.modcluster.advertise.AdvertiseListener;
import org.jboss.modcluster.advertise.AdvertiseListenerFactory;
import org.jboss.modcluster.config.BalancerConfiguration;
import org.jboss.modcluster.config.MCMPHandlerConfiguration;
import org.jboss.modcluster.config.NodeConfiguration;
import org.jboss.modcluster.load.LoadBalanceFactorProvider;
import org.jboss.modcluster.load.LoadBalanceFactorProviderFactory;
import org.jboss.modcluster.mcmp.MCMPHandler;
import org.jboss.modcluster.mcmp.MCMPRequest;
import org.jboss.modcluster.mcmp.MCMPRequestFactory;
import org.jboss.modcluster.mcmp.ResetRequestSource;

/**
 * Default implementation of {@link JBossWebEventHandler}.
 * 
 * @author Brian Stansberry
 */
public class CatalinaEventHandler implements ContainerEventHandler<Server, Engine, Context>
{   
   static final Logger log = Logger.getLogger(CatalinaEventHandler.class);

   // -----------------------------------------------------------------  Fields

   /**
    * The string manager for this package.
    */
   private final StringManager sm = StringManager.getManager(Constants.Package);
   private final NodeConfiguration nodeConfig;
   private final BalancerConfiguration balancerConfig;
   private final MCMPHandlerConfiguration mcmpConfig;
   private final MCMPHandler mcmpHandler;
   private final ResetRequestSource source;
   private final MCMPRequestFactory requestFactory;
   private final AdvertiseListenerFactory listenerFactory;
   private final LoadBalanceFactorProviderFactory loadBalanceFactorProviderFactory;
   
   private volatile Server server = null;
   
   private volatile LoadBalanceFactorProvider loadBalanceFactorProvider;
   private volatile AdvertiseListener advertiseListener;
   private volatile Map<String, Set<String>> excludedContextPaths;
   
   public CatalinaEventHandler(NodeConfiguration nodeConfig, BalancerConfiguration balancerConfig,
         MCMPHandlerConfiguration mcmpConfig, MCMPHandler mcmpHandler, ResetRequestSource source,
         MCMPRequestFactory requestFactory, LoadBalanceFactorProviderFactory loadBalanceFactorProviderFactory,
         AdvertiseListenerFactory listenerFactory)
   {
      this.nodeConfig = nodeConfig;
      this.balancerConfig = balancerConfig;
      this.mcmpConfig = mcmpConfig;
      this.mcmpHandler = mcmpHandler;
      this.source = source;
      this.requestFactory = requestFactory;
      this.loadBalanceFactorProviderFactory = loadBalanceFactorProviderFactory;
      this.listenerFactory = listenerFactory;
   }

   /**
    * @{inheritDoc}
    * @see org.jboss.modcluster.ServerProvider#getServer()
    */
   public Server getServer()
   {
      return this.server;
   }

   /**
    * @{inheritDoc}
    * @see org.jboss.modcluster.ContainerEventHandler#init(java.lang.Object)
    */
   public synchronized void init(Server server)
   {
      List<InetSocketAddress> initialProxies = Utils.parseProxies(this.mcmpConfig.getProxyList());
      
      this.mcmpHandler.init(initialProxies);
      
      this.excludedContextPaths = Utils.parseContexts(this.mcmpConfig.getExcludedContexts());

      this.source.init(this.excludedContextPaths);
      
      this.loadBalanceFactorProvider = this.loadBalanceFactorProviderFactory.createLoadBalanceFactorProvider();
      
      Boolean advertise = this.mcmpConfig.getAdvertise();
      
      if (Boolean.TRUE.equals(advertise) || (advertise == null && initialProxies.isEmpty()))
      {
         this.advertiseListener = this.listenerFactory.createListener(this.mcmpHandler, this.mcmpConfig);
         
         try
         {
            this.advertiseListener.start();
         }
         catch (IOException e)
         {
            // TODO What now?
            log.error(e.getMessage(), e);
         }
      }

      this.server = server;
   }

   /**
    * @{inheritDoc}
    * @see org.jboss.modcluster.ContainerEventHandler#shutdown()
    */
   public synchronized void shutdown()
   {
      this.server = null;
      
      if (this.advertiseListener != null)
      {
         this.advertiseListener.destroy();
         
         this.advertiseListener = null;
      }
      
      this.mcmpHandler.shutdown();
   }

   /**
    * @{inheritDoc}
    * @see org.jboss.modcluster.ContainerEventHandler#startServer(java.lang.Object)
    */
   public void startServer(Server server)
   {
      this.checkInit();

      for (Service service: server.findServices())
      {
         Engine engine = (Engine) service.getContainer();

         this.config(engine);
         
         for (Container host: engine.findChildren())
         {
            for (Container context: host.findChildren())
            {
               this.addContext((Context) context);
            }
         }
      }
   }

   /**
    * Send commands to the front end server associated with the shutdown of the
    * node.
    */
   public void stopServer(Server server)
   {
      this.checkInit();

      for (Service service: server.findServices())
      {
         Engine engine = (Engine) service.getContainer();
         
         for (Container host: engine.findChildren())
         {
            for (Container context: host.findChildren())
            {
               this.removeContext((Context) context);
            }
         }
         
         this.removeAll(engine);
      }
   }
   
   protected void config(Engine engine)
   {
      this.config(engine, this.mcmpHandler);
   }
   
   protected void config(Engine engine, MCMPHandler mcmpHandler)
   {
      log.debug(this.sm.getString("modcluster.engine.config", engine.getName()));

      // If needed, create automagical JVM route (address + port + engineName)
      try
      {
         Utils.establishJvmRouteAndConnectorAddress(engine, mcmpHandler);
         
         this.jvmRouteEstablished(engine);
         
         MCMPRequest request = this.requestFactory.createConfigRequest(engine, this.nodeConfig, this.balancerConfig);
         
         this.mcmpHandler.sendRequest(request);
      }
      catch (Exception e)
      {
         mcmpHandler.markProxiesInError();
         
         log.info(this.sm.getString("modcluster.error.addressJvmRoute"), e);
      }
   }
   
   protected void jvmRouteEstablished(Engine engine)
   {
      // Do nothing
   }

   public void addContext(Context context)
   {
      this.checkInit();

      if (!this.exclude(context))
      {
         // Send ENABLE-APP if state is started
         if (Utils.isContextStarted(context))
         {
            log.debug(this.sm.getString("modcluster.context.enable", context.getPath(), context.getParent().getName()));

            MCMPRequest request = this.requestFactory.createEnableRequest(context);
            
            this.mcmpHandler.sendRequest(request);
         }
      }
   }

   public void startContext(Context context)
   {
      this.checkInit();

      if (!this.exclude(context))
      {
         log.debug(this.sm.getString("modcluster.context.start", context.getPath(), context.getParent().getName()));
   
         // Send ENABLE-APP
         MCMPRequest request = this.requestFactory.createEnableRequest(context);
         
         this.mcmpHandler.sendRequest(request);
      }
   }

   public void stopContext(Context context)
   {
      this.checkInit();

      if (!this.exclude(context))
      {
         log.debug(this.sm.getString("modcluster.context.stop", context.getPath(), context.getParent().getName()));
   
         // Send STOP-APP
         MCMPRequest request = this.requestFactory.createStopRequest(context);
         
         this.mcmpHandler.sendRequest(request);
         Thread thr = Thread.currentThread();
         try {
            thr.sleep(5000); // Time for requests being processed.
         } catch(Exception ex) {
         }
      }
   }

   public void removeContext(Context context)
   {
      this.checkInit();

      if (!this.exclude(context))
      {
         // JVMRoute can be null here if nothing was ever initialized
         if (Utils.getJvmRoute(context) != null)
         {
            log.debug(this.sm.getString("modcluster.context.disable", context.getPath(), context.getParent().getName()));
            
            MCMPRequest request = this.requestFactory.createRemoveRequest(context);
            
            this.mcmpHandler.sendRequest(request);
         }
      }
   }

   protected void removeAll(Engine engine)
   {
      // JVMRoute can be null here if nothing was ever initialized
      if (engine.getJvmRoute() != null)
      {
         log.debug(this.sm.getString("modcluster.engine.stop", engine.getName()));

         // Send REMOVE-APP * request
         MCMPRequest request = this.requestFactory.createRemoveRequest(engine);
         
         this.mcmpHandler.sendRequest(request);
      }
   }

   public void status(Engine engine)
   {
      this.checkInit();

      log.debug(this.sm.getString("modcluster.engine.status", engine.getName()));

      this.mcmpHandler.status();

      // Send STATUS request
      Connector connector = Utils.findProxyConnector(engine.getService().findConnectors());
      int lbf = -1;
      if (connector != null && connector.isAvailable())
         lbf = this.getLoadBalanceFactor();
      MCMPRequest request = this.requestFactory.createStatusRequest(engine.getJvmRoute(), lbf);
      
      this.mcmpHandler.sendRequest(request);
   }

   protected int getLoadBalanceFactor()
   {
      return this.loadBalanceFactorProvider.getLoadBalanceFactor();
   }
   
   protected void checkInit()
   {
      if (this.server == null)
      {
         throw new IllegalStateException(this.sm.getString("modcluster.error.uninitialized"));
      }
   }
   
   private boolean exclude(Context context)
   {
      Set<String> excludedPaths = this.excludedContextPaths.get(context.getParent().getName());
      
      return (excludedPaths != null) ? excludedPaths.contains(context.getPath()) : false;
   }
}