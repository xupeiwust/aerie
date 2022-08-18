package gov.nasa.jpl.aerie.scheduler.server.services;

import gov.nasa.jpl.aerie.merlin.driver.ActivityInstanceId;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerPlugin;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.constraints.scheduling.BinaryMutexConstraint;
import gov.nasa.jpl.aerie.scheduler.constraints.scheduling.GlobalConstraint;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.model.ActivityInstance;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivityInstanceId;
import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;
import gov.nasa.jpl.aerie.scheduler.server.config.PlanOutputMode;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.ResultsProtocolFailure;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.SpecificationLoadException;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.MerlinPlan;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanMetadata;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.models.Specification;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.GoalBuilder;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
import gov.nasa.jpl.aerie.scheduler.solver.Solver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * agent that handles posed scheduling requests by blocking the requester thread until scheduling is complete
 *
 * @param merlinService interface for querying plan details from merlin
 * @param modelJarsDir path to parent directory for mission model jars (interim backdoor jar file access)
 * @param goalsJarPath path to jar file to load scheduling goals from (interim solution for user input goals)
 * @param outputMode how the scheduling output should be returned to aerie (eg overwrite or new container)
 */
//TODO: will eventually need scheduling goal service arg to pull goals from scheduler's own data store
public record SynchronousSchedulerAgent(
    SpecificationService specificationService,
    PlanService.OwnerRole merlinService,
    Path modelJarsDir,
    Path goalsJarPath,
    PlanOutputMode outputMode
)
    implements SchedulerAgent
{
  public SynchronousSchedulerAgent {
    Objects.requireNonNull(merlinService);
    Objects.requireNonNull(modelJarsDir);
    Objects.requireNonNull(goalsJarPath);
  }

  /**
   * {@inheritDoc}
   *
   * consumes any ResultsProtocolFailure exception generated by the scheduling process and writes its message as a
   * failure reason to the given output port (eg aerie could not be reached, mission model could not be loaded from jar
   * file, requested plan revision has changed in the database, scheduler could not find a solution, etc)
   *
   * any remaining exceptions passed upward represent fatal service configuration problems
   */
  @Override
  public void schedule(final ScheduleRequest request, final ResultsProtocol.WriterRole writer) {
    try {
      //confirm requested plan to schedule from/into still exists at targeted version (request could be stale)
      //TODO: maybe some kind of high level db transaction wrapping entire read/update of target plan revision

      final var specification = specificationService.getSpecification(request.specificationId());
      final var planMetadata = merlinService.getPlanMetadata(specification.planId());
      ensureRequestIsCurrent(request);
      ensurePlanRevisionMatch(specification, planMetadata.planRev());
      //create scheduler problem seeded with initial plan
      final var schedulerMissionModel = loadMissionModel(planMetadata);
      final var planningHorizon = new PlanningHorizon(
          specification.horizonStartTimestamp().toInstant(),
          specification.horizonEndTimestamp().toInstant()
      );
      final var problem = new Problem(
          schedulerMissionModel.missionModel(),
          planningHorizon,
          new SimulationFacade(planningHorizon, schedulerMissionModel.missionModel()),
          schedulerMissionModel.schedulerModel()
      );
      //seed the problem with the initial plan contents
      final var loadedPlanComponents = loadInitialPlan(planMetadata, problem);
      problem.setInitialPlan(loadedPlanComponents.schedulerPlan());

      //apply constraints/goals to the problem
      loadConstraints(planMetadata, schedulerMissionModel.missionModel()).forEach(problem::add);

      //TODO: workaround to get the Cardinality goal working. To remove once we have global constraints in the eDSL
      problem.getActivityTypes().forEach(at -> problem.add(BinaryMutexConstraint.buildMutexConstraint(at, at)));

      final var orderedGoals = new ArrayList<Goal>();
      final var goals = new HashMap<Goal, GoalId>();
      for (final var goalRecord : specification.goalsByPriority()) {
        final var goal = GoalBuilder
            .goalOfGoalSpecifier(
                goalRecord.definition(),
                specification.horizonStartTimestamp(),
                specification.horizonEndTimestamp(),
                problem::getActivityType);
        orderedGoals.add(goal);
        goals.put(goal, goalRecord.id());
      }
      problem.setGoals(orderedGoals);

      final var scheduler = createScheduler(planMetadata, problem, specification.analysisOnly());
      //run the scheduler to find a solution to the posed problem, if any
      final var solutionPlan = scheduler.getNextSolution().orElseThrow(
          () -> new ResultsProtocolFailure("scheduler returned no solution"));

      final var activityToGoalId = new HashMap<ActivityInstance, GoalId>();
      for (final var entry : solutionPlan.getEvaluation().getGoalEvaluations().entrySet()) {
        for (final var activity : entry.getValue().getInsertedActivities()) {
          activityToGoalId.put(activity, goals.get(entry.getKey()));
        }
      }
      //store the solution plan back into merlin (and reconfirm no intervening mods!)
      //TODO: make revision confirmation atomic part of plan mutation (plan might have been modified during scheduling!)
      ensurePlanRevisionMatch(specification, getMerlinPlanRev(specification.planId()));
      final var instancesToIds = storeFinalPlan(
          planMetadata,
          loadedPlanComponents.idMap(),
          loadedPlanComponents.merlinPlan(),
          solutionPlan,
          activityToGoalId
      );
      //collect results and notify subscribers of success
      final var results = collectResults(solutionPlan, instancesToIds, goals);
      writer.succeedWith(results);
    } catch (final SpecificationLoadException e) {
      //unwrap failure message from any anticipated exceptions and forward to subscribers
      writer.failWith("%s\n%s".formatted(
          e.toString(),
          SchedulingCompilationError.schedulingErrorJsonP.unparse(e.errors).toString()));
    } catch (final ResultsProtocolFailure |
        NoSuchSpecificationException |
        NoSuchPlanException |
        IOException |
        PlanServiceException e) {
      // unwrap failure message from any anticipated exceptions and forward to subscribers
      writer.failWith(e.toString());
    }
  }

  private void ensurePlanRevisionMatch(final Specification specification, final long actualPlanRev) {
    if (actualPlanRev != specification.planRevision()) {
      throw new ResultsProtocolFailure("plan with id %s at revision %d is no longer at revision %d".formatted(
          specification.planId(), actualPlanRev, specification.planRevision()));
    }
  }
  /**
   * fetch just the current revision number of the target plan from aerie services
   *
   * @param planId identifier of the target plan to load metadata for
   * @return the current revision number of the target plan according to a fresh query
   * @throws ResultsProtocolFailure when the requested plan cannot be found, or aerie could not be reached
   */
  private long getMerlinPlanRev(final PlanId planId) {
    try {
      return merlinService.getPlanRevision(planId);
    } catch (NoSuchPlanException | IOException | PlanServiceException e) {
      throw new ResultsProtocolFailure(e);
    }
  }
  /**
   * confirms that specification revision still matches that expected by the scheduling request
   *
   * @param request the original request for scheduling, containing an intended starting specification revision
   * @throws ResultsProtocolFailure when the requested specification revision does not match the actual revision
   */
  private void ensureRequestIsCurrent(final ScheduleRequest request) throws NoSuchSpecificationException {
    final var currentRevisionData = specificationService.getSpecificationRevisionData(request.specificationId());
    if (currentRevisionData.matches(request.specificationRev()) instanceof final RevisionData.MatchResult.Failure failure) {
      throw new ResultsProtocolFailure("schedule specification with id %s is stale: %s".formatted(
          request.specificationId(), failure));
    }
  }

  /**
   * collects the scheduling goals that apply to the current scheduling run on the target plan
   *
   * @param planMetadata details of the plan container whose associated goals should be collected
   * @param mission the mission model that the plan adheres to, possibly associating additional relevant goals
   * @return the list of goals relevant to the target plan
   * @throws ResultsProtocolFailure when the constraints could not be loaded, or the data stores could not be
   *     reached
   */
  private List<GlobalConstraint> loadConstraints(final PlanMetadata planMetadata, final MissionModel<?> mission) {
    //TODO: is the plan and mission model enough to find the relevant constraints? (eg what about sandbox toggling?)
    //TODO: load global constraints from scheduler data store?
    //TODO: load activity type constraints from somewhere (scheduler store? mission model?)
    //TODO: somehow apply user control over which constraints to enforce during scheduling
    return List.of();
  }

  /**
   * create a scheduler that is tuned to solve the posed problem
   *
   * @param planMetadata details of the plan container that scheduling is occurring from/into
   * @param problem specification of the scheduling problem that needs to be solved
   * @return a new scheduler that is set up to begin providing solutions to the problem
   */
  private Solver createScheduler(final PlanMetadata planMetadata, final Problem problem, final boolean analysisOnly) {
    //TODO: allow for separate control of windows for constraint analysis vs ability to schedule activities
    //      (eg constraint may need view into immutable past to know how to schedule things in the future)
    final var solver = new PrioritySolver(problem, analysisOnly);
    return solver;
  }

  /**
   * load the activity instance content of the specified merlin plan into scheduler-ready objects
   *
   * @param planMetadata metadata of plan container to load from
   * @param problem the problem that the plan adheres to
   * @return a plan with all activity instances loaded from the target merlin plan container
   * @throws ResultsProtocolFailure when the requested plan cannot be loaded, or the target plan revision has
   *     changed, or aerie could not be reached
   */
  private PlanComponents loadInitialPlan(final PlanMetadata planMetadata, final Problem problem) {
    //TODO: maybe paranoid check if plan rev has changed since original metadata?
    try {
      final var merlinPlan =  merlinService.getPlanActivities(planMetadata, problem);
      final Map<SchedulingActivityInstanceId, ActivityInstanceId> schedulingIdToMerlinId = new HashMap<>();
      final var plan = new PlanInMemory();
      final var activityTypes = problem.getActivityTypes().stream().collect(Collectors.toMap(ActivityType::getName, at -> at));
      for(final var elem : merlinPlan.getActivitiesById().entrySet()){
        final var activity = elem.getValue();
        if(!activityTypes.containsKey(activity.type())){
          throw new IllegalArgumentException("Activity type found in JSON object after request to merlin server has "
                                             + "not been found in types extracted from mission model. Probable "
                                             + "inconsistency between mission model used by scheduler server and "
                                             + "merlin server.");
        }
        final var schedulerActType = activityTypes.get(activity.type());
        final var act = new ActivityInstance(schedulerActType);
        act.setArguments(activity.arguments());
        act.setStartTime(activity.startTimestamp());
        schedulingIdToMerlinId.put(act.getId(), elem.getKey());
        if (schedulerActType.getDurationType() instanceof DurationType.Controllable s) {
          final var serializedDuration = activity.arguments().get(s.parameterName());
          if (serializedDuration != null) {
            final var duration = Duration.of(
                serializedDuration
                    .asInt()
                    .orElseThrow(() -> new Exception("Controllable Duration parameter was not an Int")),
                Duration.MICROSECONDS);
            act.setDuration(duration);
          }
        } else if (schedulerActType.getDurationType() instanceof DurationType.Uncontrollable s) {
          // Do nothing
        } else {
          throw new Error("Unhandled variant of DurationType:" + schedulerActType.getDurationType());
        }
        plan.add(act);
      }
      return new PlanComponents(plan, merlinPlan, schedulingIdToMerlinId);
    } catch (Exception e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  record PlanComponents(Plan schedulerPlan, MerlinPlan merlinPlan, Map<SchedulingActivityInstanceId, ActivityInstanceId> idMap) {}
  record SchedulerMissionModel(MissionModel<?> missionModel, SchedulerModel schedulerModel) {}

  /**
   * creates an instance of the mission model referenced by the specified plan
   *
   * @param plan metadata of the target plan indicating which mission model to load and how to configure the mission
   *     model for that plan context
   * @return instance of the mission model to extract any activity types, constraints, and simulations from
   * @throws ResultsProtocolFailure when the mission model could not be loaded: eg jar file not found, declared
   *     version/name in jar does not match, or aerie filesystem could not be mounted
   */
  private SchedulerMissionModel loadMissionModel(final PlanMetadata plan) {
    try {
      final var missionConfig = SerializedValue.of(plan.modelConfiguration());
      final var modelJarPath = modelJarsDir.resolve(plan.modelPath());
      return new SchedulerMissionModel(
          MissionModelLoader.loadMissionModel(missionConfig, modelJarPath, plan.modelName(), plan.modelVersion()),
          loadSchedulerModelProvider(modelJarPath, plan.modelName(), plan.modelVersion()).getSchedulerModel());
    } catch (MissionModelLoader.MissionModelLoadException | SchedulerModelLoadException e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  public static SchedulerPlugin loadSchedulerModelProvider(final Path path, final String name, final String version)
  throws MissionModelLoader.MissionModelLoadException, SchedulerModelLoadException
  {
    // Look for a MerlinMissionModel implementor in the mission model. For correctness, we're assuming there's
    // only one matching MerlinMissionModel in any given mission model.
    final var className = getImplementingClassName(path, name, version);

    // Construct a ClassLoader with access to classes in the mission model location.
    final var parentClassLoader = Thread.currentThread().getContextClassLoader();
    final URLClassLoader classLoader;
    try {
      classLoader = new URLClassLoader(new URL[] {path.toUri().toURL()}, parentClassLoader);
    } catch (MalformedURLException ex) {
      throw new Error(ex);
    }

    try {
      final var factoryClass$ = classLoader.loadClass(className);
      if (!SchedulerPlugin.class.isAssignableFrom(factoryClass$)) {
        throw new SchedulerModelLoadException(path, name, version);
      }

      // SAFETY: We checked above that SchedulerPlugin is assignable from this type.
      @SuppressWarnings("unchecked")
      final var factoryClass = (Class<? extends SchedulerPlugin>) factoryClass$;

      return factoryClass.getConstructor().newInstance();
    } catch (final ClassNotFoundException | NoSuchMethodException | InstantiationException
        | IllegalAccessException | InvocationTargetException ex)
    {
      throw new SchedulerModelLoadException(path, name, version, ex);
    }
  }

  public static String getImplementingClassName(final Path jarPath, final String name, final String version)
  throws SchedulerModelLoadException
  {
    try {
      final var jarFile = new JarFile(jarPath.toFile());
      final var jarEntry = jarFile.getEntry("META-INF/services/" + SchedulerPlugin.class.getCanonicalName());
      if (jarEntry == null) {
        throw new Error("JAR file `" + jarPath + "` did not declare a service called " + SchedulerPlugin.class.getCanonicalName());
      }
      final var inputStream = jarFile.getInputStream(jarEntry);

      final var classPathList = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.toList());

      if (classPathList.size() != 1) {
        throw new SchedulerModelLoadException(jarPath, name, version);
      }

      return classPathList.get(0);
    } catch (final IOException ex) {
      throw new SchedulerModelLoadException(jarPath, name, version, ex);
    }
  }

  public static class SchedulerModelLoadException extends Exception {
    private SchedulerModelLoadException(final Path path, final String name, final String version) {
      this(path, name, version, null);
    }

    private SchedulerModelLoadException(final Path path, final String name, final String version, final Throwable cause) {
      super(
          String.format(
              "No implementation found for `%s` at path `%s` wih name \"%s\" and version \"%s\"",
              SchedulerPlugin.class.getSimpleName(),
              path,
              name,
              version),
          cause);
    }
  }

  /**
   * place the modified activity plan back into the target merlin plan container
   *
   * this will obsolete the locally cached planMetadata since the plan revision will change!
   *
   * @param planMetadata metadata of plan container to store into; outdated after return
   * @param newPlan plan with all activity instances that should be stored to target merlin plan container
   * @throws ResultsProtocolFailure when the plan could not be stored to aerie, the target plan revision has
   *     changed, or aerie could not be reached
   */
  private Map<ActivityInstance, ActivityInstanceId> storeFinalPlan(
    final PlanMetadata planMetadata,
    final Map<SchedulingActivityInstanceId, ActivityInstanceId> idsFromInitialPlan,
    final MerlinPlan initialPlan,
    final Plan newPlan,
    final Map<ActivityInstance, GoalId> goalToActivity
  ) {
    try {
      switch (this.outputMode) {
        case CreateNewOutputPlan -> {
          return merlinService.createNewPlanWithActivities(planMetadata, newPlan, goalToActivity).getValue();
        }
        case UpdateInputPlanWithNewActivities -> {
          return merlinService.updatePlanActivities(
              planMetadata.planId(),
              idsFromInitialPlan,
              initialPlan,
              newPlan,
              goalToActivity
          );
        }
        default -> throw new IllegalArgumentException("unsupported scheduler output mode " + this.outputMode);
      }
    } catch (Exception e) {
      throw new ResultsProtocolFailure(e);
    }
  }

  /**
   * collect output summary of the scheduling run
   *
   * depending on service configuration, this result may be cached and served to later requesters
   *
   * only reports one evaluation's score per goal, even if the goal is scored in multiple evaluations
   *
   * @param plan the target plan after the scheduling run has completed
   * @return summary of the state of the plan after scheduling ran; eg goal success metrics, associated instances, etc
   */
  private ScheduleResults collectResults(final Plan plan, final Map<ActivityInstance, ActivityInstanceId> instancesToIds, Map<Goal, GoalId> goalsToIds) {
    Map<GoalId, ScheduleResults.GoalResult> goalResults = new HashMap<>();
      for (var goalEval : plan.getEvaluation().getGoalEvaluations().entrySet()) {
        var goalId = goalsToIds.get(goalEval.getKey());
        //goal could be anonymous, a subgoal of a composite goal for example, and thus have no meaning for results sent back
        if(goalId != null) {
          final var goalResult = new ScheduleResults.GoalResult(
              goalEval
                  .getValue()
                  .getInsertedActivities()
                  .stream()
                  .map(instancesToIds::get)
                  .collect(Collectors.toList()),
              goalEval
                  .getValue()
                  .getAssociatedActivities()
                  .stream()
                  .map(instancesToIds::get)
                  .collect(Collectors.toList()),
              goalEval.getValue().getScore() >= 0);
          goalResults.put(goalId, goalResult);
        }
      }
    return new ScheduleResults(goalResults);
  }

}
