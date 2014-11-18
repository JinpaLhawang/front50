/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.netflix.spinnaker.front50.config

import com.netflix.astyanax.Keyspace
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException
import com.netflix.spinnaker.amos.AccountCredentialsRepository
import com.netflix.spinnaker.front50.security.CassandraCredentials
import com.netflix.spinnaker.kork.astyanax.AstyanaxKeyspaceFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import javax.annotation.PostConstruct

@Configuration
@ConditionalOnExpression('${global.cassandra.enabled}')
@ConfigurationProperties(prefix = "global.cassandra")
class CassandraConfig {
  String name
  String cluster
  String keyspace

  @Autowired
  AstyanaxKeyspaceFactory factory

  @Autowired
  AccountCredentialsRepository accountCredentialsRepository

  @PostConstruct
  void init() {
    accountCredentialsRepository.save(name, new CassandraCredentials(name))
  }

  @Bean
  Keyspace keySpace() throws ConnectionException {
    return factory.getKeyspace(cluster, keyspace)
  }
}