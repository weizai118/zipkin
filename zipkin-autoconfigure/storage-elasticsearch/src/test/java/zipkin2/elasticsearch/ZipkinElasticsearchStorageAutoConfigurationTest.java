/*
 * Copyright 2015-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.elasticsearch;

import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.autoconfigure.storage.elasticsearch.Access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.util.EnvironmentTestUtils.addEnvironment;

public class ZipkinElasticsearchStorageAutoConfigurationTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  AnnotationConfigApplicationContext context;

  @After
  public void close() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  public void doesntProvideStorageComponent_whenStorageTypeNotElasticsearch() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(context, "zipkin.storage.type:cassandra");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    thrown.expect(NoSuchBeanDefinitionException.class);
    es();
  }

  @Test
  public void providesStorageComponent_whenStorageTypeElasticsearchAndHostsAreUrls() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es()).isNotNull();
  }

  @Test
  public void canOverridesProperty_hostsWithList() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200,http://host2:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().hostsSupplier().get())
        .containsExactly("http://host1:9200", "http://host2:9200");
  }

  @Test
  public void configuresPipeline() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.pipeline:zipkin");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().pipeline()).isEqualTo("zipkin");
  }

  @Test
  public void configuresMaxRequests() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.max-requests:200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().maxRequests()).isEqualTo(200);
  }

  /** This helps ensure old setups don't break (provided they have http port 9200 open) */
  @Test
  public void coersesPort9300To9200() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:host1:9300");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().hostsSupplier().get()).containsExactly("http://host1:9200");
  }

  @Test
  public void httpPrefixOptional() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:host1:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().hostsSupplier().get()).containsExactly("http://host1:9200");
  }

  @Test
  public void defaultsToPort9200() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context, "zipkin.storage.type:elasticsearch", "zipkin.storage.elasticsearch.hosts:host1");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().hostsSupplier().get()).containsExactly("http://host1:9200");
  }

  @Configuration
  static class InterceptorConfiguration {

    static Interceptor one = chain -> null;
    static Interceptor two = chain -> null;

    @Bean
    @Qualifier("zipkinElasticsearchHttp")
    Interceptor one() {
      return one;
    }

    @Bean
    @Qualifier("zipkinElasticsearchHttp")
    Interceptor two() {
      return two;
    }
  }

  /** Ensures we can wire up network interceptors, such as for logging or authentication */
  @Test
  public void usesInterceptorsQualifiedWith_zipkinElasticsearchHttp() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200");
    Access.registerElasticsearchHttp(context);
    context.register(InterceptorConfiguration.class);
    context.refresh();

    assertThat(context.getBean(OkHttpClient.class).networkInterceptors())
        .containsOnlyOnce(InterceptorConfiguration.one, InterceptorConfiguration.two);
  }

  @Test
  public void timeout_defaultsTo10Seconds() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    OkHttpClient client = context.getBean(OkHttpClient.class);
    assertThat(client.connectTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.readTimeoutMillis()).isEqualTo(10_000);
    assertThat(client.writeTimeoutMillis()).isEqualTo(10_000);
  }

  @Test
  public void timeout_override() {
    context = new AnnotationConfigApplicationContext();
    int timeout = 30_000;
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.timeout:" + timeout);
    Access.registerElasticsearchHttp(context);
    context.refresh();

    OkHttpClient client = context.getBean(OkHttpClient.class);
    assertThat(client.connectTimeoutMillis()).isEqualTo(timeout);
    assertThat(client.readTimeoutMillis()).isEqualTo(timeout);
    assertThat(client.writeTimeoutMillis()).isEqualTo(timeout);
  }

  @Test
  public void strictTraceId_defaultsToTrue() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();
    assertThat(es().strictTraceId()).isTrue();
  }

  @Test
  public void strictTraceId_canSetToFalse() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.strict-trace-id:false");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().strictTraceId()).isFalse();
  }

  @Test
  public void dailyIndexFormat() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().indexNameFormatter().formatTypeAndTimestamp("span", 0))
        .isEqualTo("zipkin:span-1970-01-01");
  }

  @Test
  public void dailyIndexFormat_overridingPrefix() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.index:zipkin_prod");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().indexNameFormatter().formatTypeAndTimestamp("span", 0))
        .isEqualTo("zipkin_prod:span-1970-01-01");
  }

  @Test
  public void dailyIndexFormat_overridingDateSeparator() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.date-separator:.");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().indexNameFormatter().formatTypeAndTimestamp("span", 0))
        .isEqualTo("zipkin:span-1970.01.01");
  }

  @Test
  public void dailyIndexFormat_overridingDateSeparator_empty() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.date-separator:");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().indexNameFormatter().formatTypeAndTimestamp("span", 0))
        .isEqualTo("zipkin:span-19700101");
  }

  @Test
  public void dailyIndexFormat_overridingDateSeparator_invalidToBeMultiChar() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.date-separator:blagho");
    Access.registerElasticsearchHttp(context);

    thrown.expect(BeanCreationException.class);
    context.refresh();
  }

  @Test
  public void namesLookbackAssignedFromQueryLookback() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.query.lookback:" + TimeUnit.DAYS.toMillis(2));
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(es().namesLookback()).isEqualTo((int) TimeUnit.DAYS.toMillis(2));
  }

  @Test
  public void doesntProvideBasicAuthInterceptor_whenBasicAuthUserNameandPasswordNotConfigured() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    thrown.expect(NoSuchBeanDefinitionException.class);
    context.getBean(Interceptor.class);
  }

  @Test
  public void providesBasicAuthInterceptor_whenBasicAuthUserNameAndPasswordConfigured() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context,
        "zipkin.storage.type:elasticsearch",
        "zipkin.storage.elasticsearch.hosts:http://host1:9200",
        "zipkin.storage.elasticsearch.username:somename",
        "zipkin.storage.elasticsearch.password:pass");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(context.getBean(OkHttpClient.class).networkInterceptors())
        .extracting(i -> i.getClass().getName())
        .contains("zipkin2.autoconfigure.storage.elasticsearch.BasicAuthInterceptor");
  }

  @Test
  public void searchEnabled_false() {
    context = new AnnotationConfigApplicationContext();
    addEnvironment(
        context, "zipkin.storage.type:elasticsearch", "zipkin.storage.search-enabled:false");
    Access.registerElasticsearchHttp(context);
    context.refresh();

    assertThat(context.getBean(ElasticsearchStorage.class).searchEnabled()).isFalse();
  }

  ElasticsearchStorage es() {
    return context.getBean(ElasticsearchStorage.class);
  }
}
