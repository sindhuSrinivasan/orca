/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.pipeline.persistence.jedis

import java.util.concurrent.Executors
import java.util.function.Function
import javax.annotation.Nonnull
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import com.netflix.spinnaker.orca.pipeline.model.Execution
import com.netflix.spinnaker.orca.pipeline.model.Execution.ExecutionType
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.model.SyntheticStageOwner
import com.netflix.spinnaker.orca.pipeline.model.Task
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionNotFoundException
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository.ExecutionCriteria
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import redis.clients.jedis.Jedis
import redis.clients.jedis.Response
import redis.clients.jedis.Transaction
import redis.clients.util.Pool
import rx.Observable
import rx.Scheduler
import rx.functions.Func1
import rx.schedulers.Schedulers
import static com.google.common.base.Predicates.notNull
import static com.google.common.collect.Maps.filterValues
import static com.netflix.spinnaker.orca.pipeline.model.Execution.DEFAULT_EXECUTION_ENGINE
import static com.netflix.spinnaker.orca.pipeline.model.Execution.ExecutionType.ORCHESTRATION
import static com.netflix.spinnaker.orca.pipeline.model.Execution.ExecutionType.PIPELINE
import static com.netflix.spinnaker.orca.pipeline.model.SyntheticStageOwner.STAGE_BEFORE
import static java.lang.System.currentTimeMillis
import static java.util.Collections.*
import static redis.clients.jedis.BinaryClient.LIST_POSITION.AFTER
import static redis.clients.jedis.BinaryClient.LIST_POSITION.BEFORE

@Component
@Slf4j
class JedisExecutionRepository implements ExecutionRepository {

  private static final TypeReference<List<Task>> LIST_OF_TASKS =
    new TypeReference<List<Task>>() {}
  private static final TypeReference<Map<String, Object>> MAP_STRING_TO_OBJECT =
    new TypeReference<Map<String, Object>>() {}

  private final Pool<Jedis> jedisPool
  private final Optional<Pool<Jedis>> jedisPoolPrevious
  private final ObjectMapper mapper = OrcaObjectMapper.newInstance()
  private final int chunkSize
  private final Scheduler queryAllScheduler
  private final Scheduler queryByAppScheduler
  private final Registry registry

  @Autowired
  JedisExecutionRepository(
    Registry registry,
    @Qualifier("jedisPool") Pool<Jedis> jedisPool,
    @Qualifier("jedisPoolPrevious") Optional<Pool<Jedis>> jedisPoolPrevious,
    @Qualifier("queryAllScheduler") Scheduler queryAllScheduler,
    @Qualifier("queryByAppScheduler") Scheduler queryByAppScheduler,
    @Value('${chunkSize.executionRepository:75}') int threadPoolChunkSize
  ) {
    this.jedisPool = jedisPool
    this.jedisPoolPrevious = jedisPoolPrevious
    this.queryAllScheduler = queryAllScheduler
    this.queryByAppScheduler = queryByAppScheduler
    this.chunkSize = threadPoolChunkSize
    this.registry = registry
  }

  JedisExecutionRepository(
    Registry registry,
    Pool<Jedis> jedisPool,
    Optional<Pool<Jedis>> jedisPoolPrevious,
    int threadPoolSize,
    int threadPoolChunkSize
  ) {
    this(
      registry,
      jedisPool,
      jedisPoolPrevious,
      Schedulers.from(Executors.newFixedThreadPool(10)),
      Schedulers.from(Executors.newFixedThreadPool(threadPoolSize)),
      threadPoolChunkSize
    )
  }

  @Override
  void store(Execution execution) {
    withJedis(getJedisPoolForId(execution.id)) { Jedis jedis ->
      jedis.multi().withCloseable { tx ->
        storeExecutionInternal(tx, execution)
        if (execution.type == PIPELINE) {
          tx.zadd(executionsByPipelineKey(execution.pipelineConfigId), execution.buildTime, execution.id)
        }
        tx.exec()
      }
    }
  }

  @Override
  void storeExecutionContext(String id, Map<String, Object> context) {
    String key = fetchKey(id)
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      jedis.watch(key)
      def committed = false
      while (!committed) {
        Map<String, Object> ctx = mapper.readValue(jedis.hget(key, "context") ?: "{}", Map)
        ctx.putAll(context)
        jedis.multi().withCloseable { tx ->
          tx.hset(key, "context", mapper.writeValueAsString(ctx))
          committed = tx.exec() != null
        }
      }
    }
  }

  @Override
  void cancel(String id) {
    cancel(id, null, null)
  }

  @Override
  void cancel(String id, String user, String reason) {
    String key = fetchKey(id)
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      def data = [canceled: "true"]
      if (user) {
        data.canceledBy = user
      }
      if (reason) {
        data.cancellationReason = reason
      }
      def currentStatus = ExecutionStatus.valueOf(jedis.hget(key, "status"))
      if (currentStatus == ExecutionStatus.NOT_STARTED) {
        data.status = ExecutionStatus.CANCELED.name()
      }
      jedis.hmset(key, data)
    }
  }

  @Override
  boolean isCanceled(String id) {
    String key = fetchKey(id)
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      Boolean.valueOf(jedis.hget(key, "canceled"))
    }
  }

  @Override
  void pause(String id, String user) {
    String key = fetchKey(id)
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      def currentStatus = ExecutionStatus.valueOf(jedis.hget(key, "status"))
      if (currentStatus != ExecutionStatus.RUNNING) {
        throw new IllegalStateException("Unable to pause pipeline that is not RUNNING (executionId: ${id}, currentStatus: ${currentStatus})")
      }

      def pausedDetails = new Execution.PausedDetails(
        pausedBy: user,
        pauseTime: currentTimeMillis()
      )

      def data = [
        paused: mapper.writeValueAsString(pausedDetails),
        status: ExecutionStatus.PAUSED.toString()
      ]
      jedis.hmset(key, data)
    }
  }

  @Override
  void resume(String id, String user, boolean ignoreCurrentStatus = false) {
    String key = fetchKey(id)
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      def currentStatus = ExecutionStatus.valueOf(jedis.hget(key, "status"))
      if (!ignoreCurrentStatus && currentStatus != ExecutionStatus.PAUSED) {
        throw new IllegalStateException("Unable to resume pipeline that is not PAUSED (executionId: ${id}, currentStatus: ${currentStatus})")
      }

      def pausedDetails = mapper.readValue(jedis.hget(key, "paused"), Execution.PausedDetails)
      pausedDetails.resumedBy = user
      pausedDetails.resumeTime = currentTimeMillis()

      def data = [
        paused: mapper.writeValueAsString(pausedDetails),
        status: ExecutionStatus.RUNNING.toString()
      ]
      jedis.hmset(key, data)
    }
  }

  @Override
  void updateStatus(String id, ExecutionStatus status) {
    String key = fetchKey(id)
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      Map<String, String> map = [status: status.name()]
      if (status == ExecutionStatus.RUNNING) {
        map.canceled = "false"
        map.startTime = String.valueOf(currentTimeMillis())
      } else if (status.complete) {
        map.endTime = String.valueOf(currentTimeMillis())
      }
      jedis.hmset(key, map)
    }
  }

  @Override
  void storeStage(Stage stage) {
    def type = stage.execution.type
    withJedis(getJedisPoolForId("${type}:${stage.execution.id}")) { Jedis jedis ->
      jedis.multi().withCloseable { tx ->
        storeStageInternal(tx, type, stage)
        tx.exec()
      }
    }
  }

  @Override
  void updateStageContext(Stage stage) {
    def execution = stage.execution
    def type = execution.type
    def key = "${type}:${execution.id}"
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      jedis.hset(key, "stage.${stage.id}.context", mapper.writeValueAsString(stage.context))
    }
  }

  @Override
  void removeStage(Execution execution, String stageId) {
    def type = execution.type
    def key = "${type}:${execution.id}"
    withJedis(getJedisPoolForId(key)) { Jedis jedis ->
      // TODO: amend this to remove hash entry
      def stageIds = jedis
        .hget(key, "stageIndex")
        .tokenize(",")
      stageIds.remove(stageId)
      def hasDiscreteIndex = jedis.exists("$key:stageIndex")
      def keys = jedis
        .hkeys(key)
        .findAll { it.startsWith("stage.$stageId.") }
        .toArray(new String[0])

      jedis.multi().withCloseable { tx ->
        tx.hset(key, "stageIndex", stageIds.join(","))
        if (hasDiscreteIndex) {
          tx.lrem("$key:stageIndex", 0, stageId)
        } else {
          tx.rpush("$key:stageIndex", *stageIds)
        }
        tx.hdel(key, keys)
        tx.exec()
      }
    }
  }

  @Override
  void addStage(Stage stage) {
    if (stage.syntheticStageOwner == null || stage.parentStageId == null) {
      throw new IllegalArgumentException("Only synthetic stages can be inserted ad-hoc")
    }

    def type = stage.execution.type
    def key = "${type}:${stage.execution.id}"
    withJedis(getJedisPoolForId(key)) { jedis ->
      jedis.multi().withCloseable { tx ->
        storeStageInternal(tx, type, stage)

        def pos = stage.syntheticStageOwner == STAGE_BEFORE ? BEFORE : AFTER
        tx.linsert("$key:stageIndex", pos, stage.parentStageId, stage.id)
        tx.exec()
      }
      // TODO: not this
      def ids = jedis.lrange("$key:stageIndex", 0, -1)
      jedis.hset(key, "stageIndex", ids.join(","))
    }
  }

  @Override
  Execution retrieve(ExecutionType type, String id) {
    withJedis(getJedisPoolForId("$type:${id}")) { Jedis jedis ->
      retrieveInternal(jedis, type, id)
    }
  }

  @Override
  void delete(ExecutionType type, String id) {
    withJedis(getJedisPoolForId("$type:${id}")) { Jedis jedis ->
      deleteInternal(jedis, type, id)
    }
  }

  @Override
  Observable<Execution> retrieve(ExecutionType type) {
    return Observable.merge(allJedis().collect { all(type, it) })
  }

  @Override
  Observable<Execution> retrievePipelinesForApplication(String application) {
    return Observable.merge(allJedis().collect {
      allForApplication(PIPELINE, application, it)
    })
  }

  @Override
  @CompileDynamic
  Observable<Execution> retrievePipelinesForPipelineConfigId(String pipelineConfigId,
                                                            ExecutionCriteria criteria) {
    /**
     * Fetch pipeline ids from the primary redis (and secondary if configured)
     */
    Map<Pool<Jedis>, List<String>> filteredPipelineIdsByJedis = [:].withDefault {
      []
    }
    if (criteria.statuses) {
      allJedis().each { Pool<Jedis> jedisPool ->
        withJedis(jedisPool) { Jedis jedis ->
          def pipelineKeys = jedis.zrevrange(executionsByPipelineKey(pipelineConfigId), 0, -1)
          def allowedExecutionStatuses = criteria.statuses*.toString() as Set<String>

          def pipeline = jedis.pipelined()
          def fetches = pipelineKeys.collect {
            pipeline.hget("pipeline:${it}" as String, "status")
          }
          pipeline.sync()

          fetches.eachWithIndex { Response<String> entry, int index ->
            if (allowedExecutionStatuses.contains(entry.get())) {
              filteredPipelineIdsByJedis[jedisPool] << pipelineKeys[index]
            }
          }
        }
      }
    }

    def fnBuilder = { Pool<Jedis> targetPool, List<String> pipelineIds ->
      new Func1<String, Iterable<String>>() {
        @Override
        Iterable<String> call(String key) {
          withJedis(targetPool) { Jedis jedis ->
            return criteria.statuses ? pipelineIds : jedis.zrevrange(key, 0, (criteria.limit - 1))
          }
        }
      }
    }

    /**
     * Construct an observable that will retrieve pipelines from the primary redis
     */
    def currentPipelineIds = filteredPipelineIdsByJedis[jedisPool]
    currentPipelineIds = currentPipelineIds.subList(0, Math.min(criteria.limit, currentPipelineIds.size()))

    def currentObservable = retrieveObservable(
      PIPELINE,
      executionsByPipelineKey(pipelineConfigId),
      fnBuilder.call(jedisPool, currentPipelineIds),
      queryByAppScheduler,
      jedisPool
    )

    if (jedisPoolPrevious.present) {
      /**
       * If configured, construct an observable the will retrieve pipelines from the secondary redis
       */
      def previousPipelineIds = filteredPipelineIdsByJedis[jedisPoolPrevious.get()]
      previousPipelineIds = previousPipelineIds - currentPipelineIds
      previousPipelineIds = previousPipelineIds.subList(0, Math.min(criteria.limit, previousPipelineIds.size()))

      def previousObservable = retrieveObservable(
        PIPELINE,
        executionsByPipelineKey(pipelineConfigId),
        fnBuilder.call(jedisPoolPrevious.get(), previousPipelineIds),
        queryByAppScheduler,
        jedisPoolPrevious.get()
      )

      // merge primary + secondary observables
      return Observable.merge(currentObservable, previousObservable)
    }

    return currentObservable
  }

  @Override
  @CompileDynamic
  Observable<Execution> retrieveOrchestrationsForApplication(String application, ExecutionCriteria criteria) {
    def allOrchestrationsKey = appKey(ORCHESTRATION, application)

    /**
     * Fetch orchestration ids from the primary redis (and secondary if configured)
     */
    Map<Pool<Jedis>, List<String>> filteredOrchestrationIdsByJedis = [:].withDefault {
      []
    }
    if (criteria.statuses) {
      allJedis().each { Pool<Jedis> targetPool ->
        withJedis(targetPool) { Jedis jedis ->
          def orchestrationKeys = jedis.smembers(allOrchestrationsKey) as List<String>
          def allowedExecutionStatuses = criteria.statuses*.toString() as Set<String>

          def pipeline = jedis.pipelined()
          def fetches = orchestrationKeys.collect {
            pipeline.hget("orchestration:${it}" as String, "status")
          }
          pipeline.sync()

          fetches.eachWithIndex { Response<String> entry, int index ->
            if (allowedExecutionStatuses.contains(entry.get())) {
              filteredOrchestrationIdsByJedis[targetPool] << orchestrationKeys[index]
            }
          }
        }
      }
    }

    def fnBuilder = { Pool<Jedis> targetPool, List<String> orchestrationIds ->
      new Func1<String, Iterable<String>>() {
        @Override
        Iterable<String> call(String key) {
          withJedis(targetPool) { Jedis jedis ->
            if (criteria.statuses) {
              return orchestrationIds
            }
            def unfiltered = jedis.smembers(key).toList()
            return unfiltered.subList(0, Math.min(criteria.limit, unfiltered.size()))
          }
        }
      }
    }

    /**
     * Construct an observable that will retrieve orchestrations from the primary redis
     */
    def currentOrchestrationIds = filteredOrchestrationIdsByJedis[jedisPool]
    currentOrchestrationIds = currentOrchestrationIds.subList(0, Math.min(criteria.limit, currentOrchestrationIds.size()))

    def currentObservable = retrieveObservable(
      ORCHESTRATION,
      allOrchestrationsKey,
      fnBuilder.call(jedisPool, currentOrchestrationIds),
      queryByAppScheduler,
      jedisPool
    )

    if (jedisPoolPrevious.present) {
      /**
       * If configured, construct an observable the will retrieve orchestrations from the secondary redis
       */
      def previousOrchestrationIds = filteredOrchestrationIdsByJedis[jedisPoolPrevious.get()]
      previousOrchestrationIds = previousOrchestrationIds - currentOrchestrationIds
      previousOrchestrationIds = previousOrchestrationIds.subList(0, Math.min(criteria.limit, previousOrchestrationIds.size()))

      def previousObservable = retrieveObservable(
        ORCHESTRATION,
        allOrchestrationsKey,
        fnBuilder.call(jedisPoolPrevious.get(), previousOrchestrationIds),
        queryByAppScheduler,
        jedisPoolPrevious.get()
      )

      // merge primary + secondary observables
      return Observable.merge(currentObservable, previousObservable)
    }

    return currentObservable
  }

  @Override
  Execution retrieveOrchestrationForCorrelationId(
    @Nonnull String correlationId) throws ExecutionNotFoundException {
    String key = "correlation:$correlationId"
    withJedis(getJedisPoolForId(key)) { Jedis correlationJedis ->
      def orchestrationId = correlationJedis.get(key)

      if (orchestrationId != null) {
        def orchestration = withJedis(getJedisPoolForId(orchestrationId)) { Jedis jedis ->
          retrieveInternal(jedis, ORCHESTRATION, orchestrationId)
        }
        if (!orchestration.status.isComplete()) {
          return orchestration
        }
        correlationJedis.del(key)
      }
      throw new ExecutionNotFoundException("No Orchestration found for correlation ID $correlationId")
    }
  }

  private void storeExecutionInternal(Transaction tx, Execution execution) {
    tx.sadd(alljobsKey(execution.type), execution.id)
    tx.sadd(appKey(execution.type, execution.application), execution.id)

    String key = "${execution.type}:$execution.id"

    Map<String, String> map = [
      application         : execution.application,
      canceled            : String.valueOf(execution.canceled),
      limitConcurrent     : String.valueOf(execution.limitConcurrent),
      buildTime           : Long.toString(execution.buildTime ?: 0L),
      startTime           : execution.startTime?.toString(),
      endTime             : execution.endTime?.toString(),
      status              : execution.status?.name(),
      authentication      : mapper.writeValueAsString(execution.authentication),
      paused              : mapper.writeValueAsString(execution.paused),
      keepWaitingPipelines: String.valueOf(execution.keepWaitingPipelines),
      executionEngine     : execution.executionEngine?.name() ?: DEFAULT_EXECUTION_ENGINE.name(),
      origin              : execution.origin?.toString(),
      trigger             : mapper.writeValueAsString(execution.trigger)
    ]
    map.stageIndex = execution.stages.id.join(",")
    // TODO: remove this and only use the list
    if (!execution.stages.empty) {
      tx.del("$key:stageIndex")
      tx.rpush("$key:stageIndex", *execution.stages.id)
    }
    execution.stages.each { stage ->
      map.putAll(serializeStage(stage))
    }
    if (execution.type == PIPELINE) {
      map.name = execution.name
      map.pipelineConfigId = execution.pipelineConfigId
      map.notifications = mapper.writeValueAsString(execution.notifications)
      map.initialConfig = mapper.writeValueAsString(execution.initialConfig)
    } else if (execution.type == ORCHESTRATION) {
      map.description = execution.description
    }
    if (execution.trigger.containsKey("correlationId")) {
      tx.set("correlation:${execution.trigger['correlationId']}", execution.id)
    }

    tx.hdel(key, "config")
    tx.hmset(key, filterValues(map, notNull()))
  }

  private Map<String, String> serializeStage(Stage stage) {
    Map<String, String> map = [:]
    map["stage.${stage.id}.refId".toString()] = stage.refId
    map["stage.${stage.id}.type".toString()] = stage.type
    map["stage.${stage.id}.name".toString()] = stage.name
    map["stage.${stage.id}.startTime".toString()] = stage.startTime?.toString()
    map["stage.${stage.id}.endTime".toString()] = stage.endTime?.toString()
    map["stage.${stage.id}.status".toString()] = stage.status.name()
    map["stage.${stage.id}.syntheticStageOwner".toString()] = stage.syntheticStageOwner?.name()
    map["stage.${stage.id}.parentStageId".toString()] = stage.parentStageId
    map["stage.${stage.id}.requisiteStageRefIds".toString()] = stage.requisiteStageRefIds?.join(",")
    map["stage.${stage.id}.scheduledTime".toString()] = stage.scheduledTime?.toString()
    map["stage.${stage.id}.context".toString()] = mapper.writeValueAsString(stage.context)
    map["stage.${stage.id}.outputs".toString()] = mapper.writeValueAsString(stage.outputs)
    map["stage.${stage.id}.tasks".toString()] = mapper.writeValueAsString(stage.tasks)
    map["stage.${stage.id}.lastModified".toString()] = stage.lastModified ? mapper.writeValueAsString(stage.lastModified) : null
    return map
  }

  private void storeStageInternal(Transaction tx, ExecutionType type, Stage stage) {
    def key = "$type:$stage.execution.id"

    def serializedStage = serializeStage(stage)
    tx.hmset(key, filterValues(serializedStage, notNull()))

    def keysToRemove = serializedStage.keySet().findAll {
      serializedStage[it] == null
    }
    if (!keysToRemove.empty) {
      tx.hdel(key, keysToRemove as String[])
    }
  }

  @CompileDynamic
  private Execution retrieveInternal(Jedis jedis, ExecutionType type, String id) throws ExecutionNotFoundException {
    def key = "$type:$id"
    if (jedis.exists(key)) {

      // read data transactionally as addStage may modify data between reading
      // stage index and pipeline hash
      Map<String, String> map
      List<String> stageIds
      jedis.multi().withCloseable { tx ->
        tx.hgetAll(key)
        tx.lrange("$key:stageIndex", 0, -1)
        def results = tx.exec()
        map = results[0]
        stageIds = results[1] ?: (map.stageIndex ?: "").tokenize(",")
      }

      def execution = new Execution(type, id, map.application)
      execution.canceled = Boolean.parseBoolean(map.canceled)
      execution.canceledBy = map.canceledBy
      execution.cancellationReason = map.cancellationReason
      execution.limitConcurrent = Boolean.parseBoolean(map.limitConcurrent)
      execution.buildTime = map.buildTime?.toLong()
      execution.startTime = map.startTime?.toLong()
      execution.endTime = map.endTime?.toLong()
      execution.status = map.status ? ExecutionStatus.valueOf(map.status) : null
      execution.authentication = mapper.readValue(map.authentication, Execution.AuthenticationDetails)
      execution.paused = map.paused ? mapper.readValue(map.paused, Execution.PausedDetails) : null
      execution.keepWaitingPipelines = Boolean.parseBoolean(map.keepWaitingPipelines)
      execution.origin = map.origin
      if (map.trigger) {
        def trigger = mapper.readValue(map.trigger, Map)
        if (trigger.containsKey("parentExecution")) {
          trigger["parentExecution"] = mapper.convertValue(trigger["parentExecution"], Execution)
        }
        execution.trigger.putAll(trigger)
      }

      try {
        execution.executionEngine = map.executionEngine == null ? DEFAULT_EXECUTION_ENGINE : Execution.ExecutionEngine.valueOf(map.executionEngine)
      } catch (IllegalArgumentException e) {
        execution.executionEngine = DEFAULT_EXECUTION_ENGINE
      }

      stageIds.each { stageId ->
        def stage = new Stage()
        stage.id = stageId
        stage.refId = map["stage.${stageId}.refId".toString()]
        stage.type = map["stage.${stageId}.type".toString()]
        stage.name = map["stage.${stageId}.name".toString()]
        stage.startTime = map["stage.${stageId}.startTime".toString()]?.toLong()
        stage.endTime = map["stage.${stageId}.endTime".toString()]?.toLong()
        stage.status = ExecutionStatus.valueOf(map["stage.${stageId}.status".toString()])
        stage.syntheticStageOwner = map["stage.${stageId}.syntheticStageOwner".toString()] ? SyntheticStageOwner.valueOf(map["stage.${stageId}.syntheticStageOwner".toString()]) : null
        stage.parentStageId = map["stage.${stageId}.parentStageId".toString()]
        stage.requisiteStageRefIds = map["stage.${stageId}.requisiteStageRefIds".toString()]?.tokenize(",") ?: emptySet()
        stage.scheduledTime = map["stage.${stageId}.scheduledTime".toString()]?.toLong()
        stage.context = map["stage.${stageId}.context".toString()] ? mapper.readValue(map["stage.${stageId}.context".toString()], MAP_STRING_TO_OBJECT) : emptyMap()
        stage.outputs = map["stage.${stageId}.outputs".toString()] ? mapper.readValue(map["stage.${stageId}.outputs".toString()], MAP_STRING_TO_OBJECT) : emptyMap()
        stage.tasks = map["stage.${stageId}.tasks".toString()] ? mapper.readValue(map["stage.${stageId}.tasks".toString()], LIST_OF_TASKS) : emptyList()
        if (map["stage.${stageId}.lastModified".toString()]) {
          stage.lastModified = map["stage.${stageId}.lastModified".toString()] ? mapper.readValue(map["stage.${stageId}.lastModified".toString()], MAP_STRING_TO_OBJECT) : emptyMap()
        }
        stage.execution = execution
        execution.stages << stage
      }
      if (execution.type == PIPELINE) {
        execution.name = map.name
        execution.pipelineConfigId = map.pipelineConfigId
        execution.notifications.addAll(mapper.readValue(map.notifications, List))
        execution.initialConfig.putAll(mapper.readValue(map.initialConfig, Map))
      } else if (execution.type == ORCHESTRATION) {
        execution.description = map.description
      }
      return execution
    } else {
      throw new ExecutionNotFoundException("No ${type} found for $id")
    }
  }

  private void deleteInternal(Jedis jedis, ExecutionType type, String id) {
    def key = "$type:$id"
    try {
      def application = jedis.hget(key, "application")
      def appKey = appKey(type, application)
      jedis.srem(appKey, id)

      if (type == PIPELINE) {
        def pipelineConfigId = jedis.hget(key, "pipelineConfigId")
        jedis.zrem(executionsByPipelineKey(pipelineConfigId), id)
      }
    } catch (ExecutionNotFoundException ignored) {
      // do nothing
    } finally {
      jedis.del(key)
      jedis.srem(alljobsKey(type), id)
    }
  }

  private Observable<Execution> all(ExecutionType type, Pool<Jedis> jedisPool) {
    retrieveObservable(type, alljobsKey(type), queryAllScheduler, jedisPool)
  }

  private Observable<Execution> allForApplication(ExecutionType type, String application, Pool<Jedis> jedisPool) {
    retrieveObservable(type, appKey(type, application), queryByAppScheduler, jedisPool)
  }

  @CompileDynamic
  private Observable<Execution> retrieveObservable(ExecutionType type,
                                                                 String lookupKey,
                                                                 Scheduler scheduler,
                                                                 Pool<Jedis> jedisPool) {
    return retrieveObservable(type, lookupKey, new Func1<String, Iterable<String>>() {
      @Override
      Iterable<String> call(String key) {
        withJedis(jedisPool) { Jedis jedis ->
          return jedis.smembers(key)
        }
      }
    }, scheduler, jedisPool)
  }

  @CompileDynamic
  private Observable<Execution> retrieveObservable(ExecutionType type,
                                                                 String lookupKey,
                                                                 Func1<String, Iterable<String>> lookupKeyFetcher,
                                                                 Scheduler scheduler,
                                                                 Pool<Jedis> jedisPool) {
    Observable
      .just(lookupKey)
      .flatMapIterable(lookupKeyFetcher)
      .buffer(chunkSize)
      .flatMap { Collection<String> ids ->
      Observable
        .from(ids)
        .flatMap { String executionId ->
        withJedis(jedisPool) { Jedis jedis ->
          try {
            return Observable.just(retrieveInternal(jedis, type, executionId))
          } catch (ExecutionNotFoundException ignored) {
            log.info("Execution (${executionId}) does not exist")
            if (jedis.type(lookupKey) == "zset") {
              jedis.zrem(lookupKey, executionId)
            } else {
              jedis.srem(lookupKey, executionId)
            }
          } catch (Exception e) {
            log.error("Failed to retrieve execution '${executionId}', message: ${e.message}", e)
          }
          return Observable.empty()
        }
      }
      .subscribeOn(scheduler)
    }
  }

  private static String alljobsKey(ExecutionType type) {
    "allJobs:${type}"
  }

  private static String appKey(ExecutionType type, String app) {
    "${type}:app:${app}"
  }

  static String executionsByPipelineKey(String pipelineConfigId) {
    pipelineConfigId = pipelineConfigId ?: "---"
    "pipeline:executions:$pipelineConfigId"
  }

  private String fetchKey(String id) {
    String key = withJedis(jedisPool) { Jedis jedis ->
      if (jedis.exists("pipeline:$id")) {
        return "pipeline:$id"
      } else if (jedis.exists("orchestration:$id")) {
        return "orchestration:$id"
      }
      return null
    }

    if (!key && jedisPoolPrevious.present) {
      key = withJedis(jedisPoolPrevious.get()) { Jedis jedis ->
        if (jedis.exists("pipeline:$id")) {
          return "pipeline:$id"
        } else if (jedis.exists("orchestration:$id")) {
          return "orchestration:$id"
        }
        return null
      }
    }

    if (!key) {
      throw new ExecutionNotFoundException("No execution found with id $id")
    }

    return key
  }

  private Pool<Jedis> getJedisPoolForId(String id) {
    if (!id) {
      return jedisPool
    }

    Pool<Jedis> jedisPoolForId = null
    withJedis(jedisPool) { Jedis jedis ->
      if (jedis.exists(id)) {
        jedisPoolForId = jedisPool
      }
    }

    if (!jedisPoolForId && jedisPoolPrevious.present) {
      withJedis(jedisPoolPrevious.get()) { Jedis jedis ->
        if (jedis.exists(id)) {
          jedisPoolForId = jedisPoolPrevious.get()
        }
      }
    }

    return jedisPoolForId ?: jedisPool
  }

  private <T> T withJedis(Pool<Jedis> jedisPool, Function<Jedis, T> action) {
    jedisPool.resource.withCloseable(action.&apply)
  }

  private Collection<Pool<Jedis>> allJedis() {
    return ([jedisPool] + (jedisPoolPrevious.present ? [jedisPoolPrevious.get()] : []))
  }
}
