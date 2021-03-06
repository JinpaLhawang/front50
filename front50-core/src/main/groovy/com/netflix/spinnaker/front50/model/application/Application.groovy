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


package com.netflix.spinnaker.front50.model.application

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.front50.events.ApplicationEventListener
import com.netflix.spinnaker.front50.exception.ApplicationAlreadyExistsException
import com.netflix.spinnaker.front50.exception.NotFoundException
import com.netflix.spinnaker.front50.model.Timestamped
import com.netflix.spinnaker.front50.validator.ApplicationValidationErrors
import com.netflix.spinnaker.front50.validator.ApplicationValidator
import groovy.transform.Canonical
import groovy.transform.ToString
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import groovy.util.logging.Slf4j
import org.springframework.validation.Errors

@ToString
@Slf4j
class Application implements Timestamped {
  String name
  String description
  String email
  String accounts
  String updateTs
  String createTs

  private Map<String, Object> details = new HashMap<String, Object>()

  String getName() {
    // there is an expectation that application names are uppercased (historical)
    return name?.toUpperCase()
  }

  @JsonAnyGetter
  public Map<String,Object> details() {
    return details;
  }

  @JsonAnySetter
  public void set(String name, Object value) {
    details.put(name, value);
  }

  @JsonIgnore
  public Map<String, Object> getPersistedProperties() {
    [
        name: this.name,
        description: this.description,
        email: this.email,
        accounts: this.accounts,
        updateTs: this.updateTs,
        createTs: this.createTs,
        details: this.details
    ]
  }

  @JsonIgnore
  ApplicationDAO dao

  @JsonIgnore
  Collection<ApplicationValidator> validators

  @JsonIgnore
  Collection<ApplicationEventListener> applicationEventListeners

  void update(Application updatedApplication) {

    updatedApplication.name = this.name
    updatedApplication.createTs = this.createTs
    updatedApplication.description = updatedApplication.description ?: this.description
    updatedApplication.email = updatedApplication.email ?: this.email
    updatedApplication.accounts = updatedApplication.accounts ?: this.accounts
    mergeDetails(updatedApplication, this)
    validate(updatedApplication)

    perform(
        applicationEventListeners.findAll { it.supports(ApplicationEventListener.Type.PRE_UPDATE) },
        applicationEventListeners.findAll { it.supports(ApplicationEventListener.Type.POST_UPDATE) },
        { Application originalApplication, Application modifiedApplication ->
          // onSuccess
          this.dao.update(originalApplication.name.toUpperCase(), modifiedApplication)
          updatedApplication.updateTs = originalApplication.updateTs
          return modifiedApplication
        },
        { Application originalApplication, Application modifiedApplication ->
          // onRollback
          this.dao.update(originalApplication.name.toUpperCase(), originalApplication)
        },
        this,
        updatedApplication
    )
  }

  private static void mergeDetails(Application target, Application source) {
    source.details.each { String key, Object value ->
      if (!target.details.containsKey(key)) {
        target.details[key] = value
      }
    }
  }

  void delete() {
    Application currentApplication = null
    try {
      currentApplication = findByName(this.name)
    } catch (NotFoundException ignored) {
      // do nothing
    }

    if (!currentApplication) {
      log.warn("Application does not exist (name: ${name}), nothing to delete")
      return
    }

    perform(
        applicationEventListeners.findAll { it.supports(ApplicationEventListener.Type.PRE_DELETE) },
        applicationEventListeners.findAll { it.supports(ApplicationEventListener.Type.POST_DELETE) },
        { Application originalApplication, Application modifiedApplication ->
          // onSuccess
          this.dao.delete(currentApplication.name)
          return null
        },
        { Application originalApplication, Application modifiedApplication ->
          // onRollback
          this.dao.create(currentApplication.name, currentApplication)
          return null
        },
        currentApplication,
        null
    )
  }

  Application clear() {
    getPersistedProperties().keySet().each { field ->
      this[field] = null
    }
    this.details = [:]
    return this
  }

  /**
   * Similar to clone but doesn't produce a copy
   */
  Application initialize(Application app) {
    this.clear()
    getPersistedProperties().keySet().each { key ->
      this[key] = app[key]
    }
    return this
  }

  Application save() {
    validate(this)

    try {
      if (findByName(getName())) {
        throw new ApplicationAlreadyExistsException()
      }
    } catch (NotFoundException ignored) {}

    return perform(
        applicationEventListeners.findAll { it.supports(ApplicationEventListener.Type.PRE_CREATE) },
        applicationEventListeners.findAll { it.supports(ApplicationEventListener.Type.POST_CREATE) },
        { Application originalApplication, Application modifiedApplication ->
          // onSuccess
          return dao.create(modifiedApplication.name.toUpperCase(), modifiedApplication)
        },
        { Application originalApplication, Application modifiedApplication ->
          // onRollback
          this.dao.delete(modifiedApplication.name.toUpperCase())
          return null
        },
        null,
        this
    )
  }

  Collection<Application> findAll() {
    try {
      return dao.all() ?: []
    } catch (NotFoundException ignored) {
      return []
    }
  }

  Application findByName(String name) throws NotFoundException {
    if (!name?.trim()) {
      throw new NotFoundException("No application name provided")
    }

    return dao.findByName(name.toUpperCase())
  }

  Set<Application> search(Map<String, String> params) {
    try {
      return dao.search(params) ?: []
    } catch (NotFoundException ignored) {
      return []
    }
  }

  Application withName(String name) {
    this.name = name
    return this
  }

  private void validate(Application application) {
    def errors = new ApplicationValidationErrors(application)
    validators.each {
      it.validate(application, errors)
    }

    if (errors.hasErrors()) {
      throw new ValidationException(errors)
    }
  }

  static Application perform(List<ApplicationEventListener> preApplicationEventListeners,
                             List<ApplicationEventListener> postApplicationEventListeners,
                             @ClosureParams(value = SimpleType, options = [
                                 'com.netflix.spinnaker.front50.model.application.Application',
                                 'com.netflix.spinnaker.front50.model.application.Application'
                             ]) Closure<Application> onSuccess,
                             @ClosureParams(value = SimpleType, options = [
                                 'com.netflix.spinnaker.front50.model.application.Application',
                                 'com.netflix.spinnaker.front50.model.application.Application'
                             ]) Closure<Void> onRollback,
                             Application originalApplication,
                             Application updatedApplication) {
    def copyOfOriginalApplication = copy(originalApplication)

    def invokedEventListeners = []
    try {
      preApplicationEventListeners.each {
        updatedApplication = it.call(copy(copyOfOriginalApplication), copy(updatedApplication)) as Application
        invokedEventListeners << it
      }
      updatedApplication = onSuccess.call(copy(copyOfOriginalApplication), copy(updatedApplication))
      postApplicationEventListeners.each {
        updatedApplication = it.call(copy(copyOfOriginalApplication), copy(updatedApplication))
        invokedEventListeners << it
      }

      return updatedApplication
    } catch (Exception e) {
      invokedEventListeners.each {
        try {
          it.rollback(copy(copyOfOriginalApplication))
        } catch (Exception rollbackException) {
          log.error("Rollback failed (${it.class.simpleName})", rollbackException)
        }
      }
      try {
        onRollback.call(copy(copyOfOriginalApplication), copy(updatedApplication))
      } catch (Exception rollbackException) {
        log.error("Rollback failed (onRollback)", rollbackException)
      }

      log.error("Failed to perform action (name: ${originalApplication?.name ?: updatedApplication?.name})")
      throw e
    }
  }

  @Override
  @JsonIgnore()
  String getId() {
    return name.toLowerCase()
  }

  @Override
  @JsonIgnore
  Long getLastModified() {
    return updateTs ? Long.valueOf(updateTs) : null
  }

  @Override
  void setLastModified(Long lastModified) {
    this.updateTs = lastModified.toString()
  }

  private static Application copy(Application source) {
    return source ? new Application(source.getPersistedProperties()) : null
  }

  @Canonical
  static class ValidationException extends RuntimeException {
    Errors errors
  }

  static class Permission implements Timestamped {
    String name
    Long lastModified
    List<String> requiredGroupMembership

    @Override
    @JsonIgnore()
    String getId() {
      return name.toLowerCase()
    }
  }
}
