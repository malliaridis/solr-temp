/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.scripting.xslt;

import static org.apache.solr.scripting.xslt.XSLTConstants.CONTEXT_TRANSFORMER_KEY;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;
import org.apache.lucene.util.ResourceLoader;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.TimeSources;
import org.apache.solr.common.util.XMLErrorLogger;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.util.SystemIdResolver;
import org.apache.solr.util.TimeOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton that creates a Transformer for XSLT For now, only caches the last created Transformer,
 * but could evolve to use an LRU cache of Transformers.
 *
 * <p>See http://www.javaworld.com/javaworld/jw-05-2003/jw-0502-xsl_p.html for one possible way of
 * improving caching.
 */
class TransformerProvider {
  private String lastFilename;
  private Templates lastTemplates = null;
  private TimeOut cacheExpiresTimeout;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final XMLErrorLogger xmllog = new XMLErrorLogger(log);

  public static TransformerProvider instance = new TransformerProvider();

  /** singleton */
  private TransformerProvider() {
    // tell'em: currently, we only cache the last used XSLT transform, and blindly recompile it
    // once cacheLifetimeSeconds expires
    log.warn(
        "The TransformerProvider's simplistic XSLT caching mechanism is not appropriate "
            + "for high load scenarios, unless a single XSLT transform is used"
            + " and xsltCacheLifetimeSeconds is set to a sufficiently high value.");
  }

  /**
   * Get Transformer from request context, or from TransformerProvider. This allows either
   * getContentType(...) or write(...) to instantiate the Transformer, depending on which one is
   * called first, then the other one reuses the same Transformer
   */
  static Transformer getTransformer(
      SolrQueryRequest request, String xslt, int xsltCacheLifetimeSeconds) throws IOException {
    // not the cleanest way to achieve this
    // no need to synchronize access to context, right?
    // Nothing else happens with it at the same time
    final Map<Object, Object> ctx = request.getContext();
    Transformer result = (Transformer) ctx.get(CONTEXT_TRANSFORMER_KEY);
    if (result == null) {
      SolrConfig solrConfig = request.getCore().getSolrConfig();
      result = instance.getTransformer(solrConfig, xslt, xsltCacheLifetimeSeconds);
      result.setErrorListener(xmllog);
      ctx.put(CONTEXT_TRANSFORMER_KEY, result);
    }
    return result;
  }

  /**
   * Return a new Transformer, possibly created from our cached Templates object
   *
   * @throws IOException If there is a low-level I/O error.
   */
  public synchronized Transformer getTransformer(
      SolrConfig solrConfig, String filename, int cacheLifetimeSeconds) throws IOException {
    // For now, the Templates are blindly reloaded once cacheExpires is over.
    // It'd be better to check the file modification time to reload only if needed.
    if (lastTemplates != null
        && filename.equals(lastFilename)
        && cacheExpiresTimeout != null
        && !cacheExpiresTimeout.hasTimedOut()) {
      if (log.isDebugEnabled()) {
        log.debug("Using cached Templates:{}", filename);
      }
    } else {
      lastTemplates = getTemplates(solrConfig.getResourceLoader(), filename, cacheLifetimeSeconds);
    }

    Transformer result = null;

    try {
      result = lastTemplates.newTransformer();
    } catch (TransformerConfigurationException tce) {
      log.error(getClass().getName(), "getTransformer", tce);
      throw new IOException("newTransformer fails ( " + lastFilename + ")", tce);
    }

    return result;
  }

  /** Return a Templates object for the given filename */
  private Templates getTemplates(ResourceLoader loader, String filename, int cacheLifetimeSeconds)
      throws IOException {

    Templates result = null;
    lastFilename = null;
    try {
      if (log.isDebugEnabled()) {
        log.debug("compiling XSLT templates:{}", filename);
      }
      final String fn = "xslt/" + filename;
      final TransformerFactory tFactory = TransformerFactory.newInstance();
      tFactory.setURIResolver(new SystemIdResolver(loader).asURIResolver());
      tFactory.setErrorListener(xmllog);
      final StreamSource src =
          new StreamSource(
              loader.openResource(fn), SystemIdResolver.createSystemIdFromResourceName(fn));
      try {
        result = tFactory.newTemplates(src);
      } finally {
        // some XML parsers are broken and don't close the byte stream (but they should according to
        // spec)
        IOUtils.closeQuietly(src.getInputStream());
      }
    } catch (Exception e) {
      log.error(getClass().getName(), "newTemplates", e);
      throw new IOException("Unable to initialize Templates '" + filename + "'", e);
    }

    lastFilename = filename;
    lastTemplates = result;
    cacheExpiresTimeout =
        new TimeOut(cacheLifetimeSeconds, TimeUnit.SECONDS, TimeSources.NANO_TIME);

    return result;
  }
}
