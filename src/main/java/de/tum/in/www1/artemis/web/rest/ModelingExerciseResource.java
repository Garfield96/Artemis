package de.tum.in.www1.artemis.web.rest;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.domain.modeling.ModelingExercise;
import de.tum.in.www1.artemis.domain.plagiarism.modeling.ModelingPlagiarismResult;
import de.tum.in.www1.artemis.repository.*;
import de.tum.in.www1.artemis.security.Role;
import de.tum.in.www1.artemis.service.*;
import de.tum.in.www1.artemis.service.feature.Feature;
import de.tum.in.www1.artemis.service.feature.FeatureToggle;
import de.tum.in.www1.artemis.service.messaging.InstanceMessageSendService;
import de.tum.in.www1.artemis.service.notifications.GroupNotificationService;
import de.tum.in.www1.artemis.service.plagiarism.ModelingPlagiarismDetectionService;
import de.tum.in.www1.artemis.service.util.TimeLogUtil;
import de.tum.in.www1.artemis.web.rest.dto.PageableSearchDTO;
import de.tum.in.www1.artemis.web.rest.dto.SearchResultPageDTO;
import de.tum.in.www1.artemis.web.rest.dto.SubmissionExportOptionsDTO;
import de.tum.in.www1.artemis.web.rest.errors.BadRequestAlertException;
import de.tum.in.www1.artemis.web.rest.errors.ConflictException;
import de.tum.in.www1.artemis.web.rest.util.HeaderUtil;
import de.tum.in.www1.artemis.web.rest.util.ResponseUtil;

/** REST controller for managing ModelingExercise. */
@RestController
@RequestMapping(ModelingExerciseResource.Endpoints.ROOT)
public class ModelingExerciseResource {

    private final Logger log = LoggerFactory.getLogger(ModelingExerciseResource.class);

    private static final String ENTITY_NAME = "modelingExercise";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final ModelingExerciseRepository modelingExerciseRepository;

    private final UserRepository userRepository;

    private final CourseRepository courseRepository;

    private final CourseService courseService;

    private final ParticipationRepository participationRepository;

    private final AuthorizationCheckService authCheckService;

    private final ModelingExerciseService modelingExerciseService;

    private final ExerciseService exerciseService;

    private final ExerciseDeletionService exerciseDeletionService;

    private final PlagiarismResultRepository plagiarismResultRepository;

    private final ModelingExerciseImportService modelingExerciseImportService;

    private final SubmissionExportService modelingSubmissionExportService;

    private final GroupNotificationService groupNotificationService;

    private final GradingCriterionRepository gradingCriterionRepository;

    private final ModelingPlagiarismDetectionService modelingPlagiarismDetectionService;

    private final InstanceMessageSendService instanceMessageSendService;

    private final ModelClusterRepository modelClusterRepository;

    private final ModelAssessmentKnowledgeService modelAssessmentKnowledgeService;

    public ModelingExerciseResource(ModelingExerciseRepository modelingExerciseRepository, UserRepository userRepository, CourseService courseService,
            AuthorizationCheckService authCheckService, CourseRepository courseRepository, ParticipationRepository participationRepository,
            ModelingExerciseService modelingExerciseService, ExerciseDeletionService exerciseDeletionService, PlagiarismResultRepository plagiarismResultRepository,
            ModelingExerciseImportService modelingExerciseImportService, SubmissionExportService modelingSubmissionExportService, GroupNotificationService groupNotificationService,
            ExerciseService exerciseService, GradingCriterionRepository gradingCriterionRepository, ModelingPlagiarismDetectionService modelingPlagiarismDetectionService,
            InstanceMessageSendService instanceMessageSendService, ModelClusterRepository modelClusterRepository, ModelAssessmentKnowledgeService modelAssessmentKnowledgeService) {
        this.modelingExerciseRepository = modelingExerciseRepository;
        this.courseService = courseService;
        this.modelingExerciseService = modelingExerciseService;
        this.exerciseDeletionService = exerciseDeletionService;
        this.plagiarismResultRepository = plagiarismResultRepository;
        this.modelingExerciseImportService = modelingExerciseImportService;
        this.modelingSubmissionExportService = modelingSubmissionExportService;
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.participationRepository = participationRepository;
        this.authCheckService = authCheckService;
        this.groupNotificationService = groupNotificationService;
        this.exerciseService = exerciseService;
        this.gradingCriterionRepository = gradingCriterionRepository;
        this.modelingPlagiarismDetectionService = modelingPlagiarismDetectionService;
        this.instanceMessageSendService = instanceMessageSendService;
        this.modelClusterRepository = modelClusterRepository;
        this.modelAssessmentKnowledgeService = modelAssessmentKnowledgeService;
    }

    // TODO: most of these calls should be done in the context of a course

    /**
     * POST modeling-exercises : Create a new modelingExercise.
     *
     * @param modelingExercise the modelingExercise to create
     * @return the ResponseEntity with status 201 (Created) and with body the new modelingExercise, or with status 400 (Bad Request) if the modelingExercise has already an ID
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    // TODO: we should add courses/{courseId} here
    @PostMapping("modeling-exercises")
    @PreAuthorize("hasRole('EDITOR')")
    public ResponseEntity<ModelingExercise> createModelingExercise(@RequestBody ModelingExercise modelingExercise) throws URISyntaxException {
        log.debug("REST request to save ModelingExercise : {}", modelingExercise);
        if (modelingExercise.getId() != null) {
            throw new BadRequestAlertException("A new modeling exercise cannot already have an ID", ENTITY_NAME, "idexists");
        }
        if (modelingExercise.getTitle() == null) {
            throw new BadRequestAlertException("A new modeling exercise needs a title", ENTITY_NAME, "missingtitle");
        }
        // validates general settings: points, dates
        modelingExercise.validateGeneralSettings();
        // Valid exercises have set either a course or an exerciseGroup
        modelingExercise.checkCourseAndExerciseGroupExclusivity(ENTITY_NAME);

        // Retrieve the course over the exerciseGroup or the given courseId
        Course course = courseService.retrieveCourseOverExerciseGroupOrCourseId(modelingExercise);
        // Check that the user is authorized to create the exercise
        authCheckService.checkHasAtLeastRoleInCourseElseThrow(Role.EDITOR, course, null);

        // if exercise is created from scratch we create new knowledge instance
        modelingExercise.setKnowledge(modelAssessmentKnowledgeService.createNewKnowledge());
        ModelingExercise result = modelingExerciseRepository.save(modelingExercise);
        modelingExerciseService.scheduleOperations(result.getId());
        groupNotificationService.checkNotificationsForNewExercise(modelingExercise, instanceMessageSendService);

        return ResponseEntity.created(new URI("/api/modeling-exercises/" + result.getId()))
                .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, result.getId().toString())).body(result);
    }

    /**
     * Search for all modeling exercises by title and course title. The result is pageable since there might be hundreds
     * of exercises in the DB.
     *
     * @param search The pageable search containing the page size, page number and query string
     * @return The desired page, sorted and matching the given query
     */
    @GetMapping("modeling-exercises")
    @PreAuthorize("hasRole('EDITOR')")
    public ResponseEntity<SearchResultPageDTO<ModelingExercise>> getAllExercisesOnPage(PageableSearchDTO<String> search) {
        final var user = userRepository.getUserWithGroupsAndAuthorities();
        return ResponseEntity.ok(modelingExerciseService.getAllOnPageWithSize(search, user));
    }

    /**
     * PUT modeling-exercises : Updates an existing modelingExercise.
     *
     * @param modelingExercise the modelingExercise to update
     * @param notificationText the text shown to students
     * @return the ResponseEntity with status 200 (OK) and with body the updated modelingExercise, or with status 400 (Bad Request) if the modelingExercise is not valid, or with
     *         status 500 (Internal Server Error) if the modelingExercise couldn't be updated
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PutMapping("modeling-exercises")
    @PreAuthorize("hasRole('EDITOR')")
    public ResponseEntity<ModelingExercise> updateModelingExercise(@RequestBody ModelingExercise modelingExercise,
            @RequestParam(value = "notificationText", required = false) String notificationText) throws URISyntaxException {
        log.debug("REST request to update ModelingExercise : {}", modelingExercise);
        if (modelingExercise.getId() == null) {
            return createModelingExercise(modelingExercise);
        }
        // validates general settings: points, dates
        modelingExercise.validateGeneralSettings();
        // Valid exercises have set either a course or an exerciseGroup
        modelingExercise.checkCourseAndExerciseGroupExclusivity(ENTITY_NAME);

        // Check that the user is authorized to update the exercise
        var user = userRepository.getUserWithGroupsAndAuthorities();
        // Important: use the original exercise for permission check
        final ModelingExercise modelingExerciseBeforeUpdate = modelingExerciseRepository.findByIdElseThrow(modelingExercise.getId());
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.EDITOR, modelingExerciseBeforeUpdate, user);

        // Forbid changing the course the exercise belongs to.
        if (!Objects.equals(modelingExerciseBeforeUpdate.getCourseViaExerciseGroupOrCourseMember().getId(), modelingExercise.getCourseViaExerciseGroupOrCourseMember().getId())) {
            throw new ConflictException("Exercise course id does not match the stored course id", ENTITY_NAME, "cannotChangeCourseId");
        }

        // Forbid conversion between normal course exercise and exam exercise
        exerciseService.checkForConversionBetweenExamAndCourseExercise(modelingExercise, modelingExerciseBeforeUpdate, ENTITY_NAME);

        ModelingExercise updatedModelingExercise = modelingExerciseRepository.save(modelingExercise);
        exerciseService.logUpdate(modelingExercise, modelingExercise.getCourseViaExerciseGroupOrCourseMember(), user);
        exerciseService.updatePointsInRelatedParticipantScores(modelingExerciseBeforeUpdate, updatedModelingExercise);

        participationRepository.removeIndividualDueDatesIfBeforeDueDate(updatedModelingExercise, modelingExerciseBeforeUpdate.getDueDate());
        modelingExerciseService.scheduleOperations(updatedModelingExercise.getId());
        exerciseService.checkExampleSubmissions(updatedModelingExercise);

        groupNotificationService.checkAndCreateAppropriateNotificationsWhenUpdatingExercise(modelingExerciseBeforeUpdate, updatedModelingExercise, notificationText,
                instanceMessageSendService);

        return ResponseEntity.ok().headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, modelingExercise.getId().toString()))
                .body(updatedModelingExercise);
    }

    /**
     * GET /courses/:courseId/exercises : get all the exercises.
     *
     * @param courseId the id of the course
     * @return the ResponseEntity with status 200 (OK) and the list of modelingExercises in body
     */
    @GetMapping(value = "/courses/{courseId}/modeling-exercises")
    @PreAuthorize("hasRole('TA')")
    public ResponseEntity<List<ModelingExercise>> getModelingExercisesForCourse(@PathVariable Long courseId) {
        log.debug("REST request to get all ModelingExercises for the course with id : {}", courseId);
        Course course = courseRepository.findByIdElseThrow(courseId);
        authCheckService.checkHasAtLeastRoleInCourseElseThrow(Role.TEACHING_ASSISTANT, course, null);
        List<ModelingExercise> exercises = modelingExerciseRepository.findByCourseIdWithCategories(courseId);
        for (Exercise exercise : exercises) {
            // not required in the returned json body
            exercise.setStudentParticipations(null);
            exercise.setCourse(null);
        }
        return ResponseEntity.ok().body(exercises);
    }

    /**
     * GET modeling-exercises/:exerciseId : get the "id" modelingExercise.
     *
     * @param exerciseId the id of the modelingExercise to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the modelingExercise, or with status 404 (Not Found)
     */
    @GetMapping("modeling-exercises/{exerciseId}")
    @PreAuthorize("hasRole('TA')")
    public ResponseEntity<ModelingExercise> getModelingExercise(@PathVariable Long exerciseId) {
        log.debug("REST request to get ModelingExercise : {}", exerciseId);
        var modelingExercise = modelingExerciseRepository.findWithEagerExampleSubmissionsByIdElseThrow(exerciseId);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.TEACHING_ASSISTANT, modelingExercise, null);
        List<GradingCriterion> gradingCriteria = gradingCriterionRepository.findByExerciseIdWithEagerGradingCriteria(exerciseId);
        modelingExercise.setGradingCriteria(gradingCriteria);

        exerciseService.checkExerciseIfStructuredGradingInstructionFeedbackUsed(gradingCriteria, modelingExercise);

        return ResponseEntity.ok().body(modelingExercise);
    }

    /**
     * DELETE modeling-exercises/:id : delete the "id" modelingExercise.
     *
     * @param exerciseId the id of the modelingExercise to delete
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("modeling-exercises/{exerciseId}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<Void> deleteModelingExercise(@PathVariable Long exerciseId) {
        log.info("REST request to delete ModelingExercise : {}", exerciseId);
        var modelingExercise = modelingExerciseRepository.findByIdElseThrow(exerciseId);

        modelingExerciseService.cancelScheduledOperations(exerciseId);

        User user = userRepository.getUserWithGroupsAndAuthorities();
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.INSTRUCTOR, modelingExercise, user);
        // note: we use the exercise service here, because this one makes sure to clean up all lazy references correctly.
        exerciseService.logDeletion(modelingExercise, modelingExercise.getCourseViaExerciseGroupOrCourseMember(), user);
        exerciseDeletionService.delete(exerciseId, false, false);
        return ResponseEntity.ok().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, modelingExercise.getTitle())).build();
    }

    /**
     * DELETE modeling-exercises/:id/clusters : delete the clusters and elements of "id" modelingExercise.
     *
     * @param exerciseId the id of the modelingExercise to delete clusters and elements
     * @return the ResponseEntity with status 200 (OK)
     */
    @GetMapping("modeling-exercises/{exerciseId}/check-clusters")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Integer> checkClusters(@PathVariable Long exerciseId) {
        log.info("REST request to check clusters of ModelingExercise : {}", exerciseId);
        var modelingExercise = modelingExerciseRepository.findByIdElseThrow(exerciseId);
        int clusterCount = modelClusterRepository.countByExerciseIdWithEagerElements(exerciseId);
        User user = userRepository.getUserWithGroupsAndAuthorities();
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.ADMIN, modelingExercise, user);
        return ResponseEntity.ok().body(clusterCount);
    }

    /**
     * DELETE modeling-exercises/:id/clusters : delete the clusters and elements of "id" modelingExercise.
     *
     * @param exerciseId the id of the modelingExercise to delete clusters and elements
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("modeling-exercises/{exerciseId}/clusters")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteModelingExerciseClustersAndElements(@PathVariable Long exerciseId) {
        log.info("REST request to delete ModelingExercise : {}", exerciseId);
        var modelingExercise = modelingExerciseRepository.findByIdElseThrow(exerciseId);

        User user = userRepository.getUserWithGroupsAndAuthorities();
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.ADMIN, modelingExercise, user);
        modelingExerciseService.deleteClustersAndElements(modelingExercise);
        return ResponseEntity.ok().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, modelingExercise.getTitle())).build();
    }

    /**
     * POST modeling-exercises/{exerciseId}/trigger-automatic-assessment: trigger automatic assessment
     * (clustering task) for given exercise id As the clustering can be performed on a different
     * node, this will always return 200, despite an error could occur on the other node.
     *
     * @param exerciseId id of the exercised that for which the automatic assessment should be
     *                   triggered
     * @return the ResponseEntity with status 200 (OK)
     */
    @PostMapping("modeling-exercises/{exerciseId}/trigger-automatic-assessment")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> triggerAutomaticAssessment(@PathVariable Long exerciseId) {
        instanceMessageSendService.sendModelingExerciseInstantClustering(exerciseId);
        return ResponseEntity.ok().build();
    }

    /**
     * POST modeling-exercises/import: Imports an existing modeling exercise into an existing course
     *
     * This will import the whole exercise except for the participations and Dates.
     * Referenced entities will get cloned and assigned a new id.
     * Uses {@link ModelingExerciseImportService}.
     *
     * @param sourceExerciseId The ID of the original exercise which should get imported
     * @param importedExercise The new exercise containing values that should get overwritten in the imported exercise, s.a. the title or difficulty
     * @throws URISyntaxException When the URI of the response entity is invalid
     *
     * @return The imported exercise (200), a not found error (404) if the template does not exist, or a forbidden error
     *         (403) if the user is not at least an instructor in the target course.
     */
    @PostMapping("modeling-exercises/import/{sourceExerciseId}")
    @PreAuthorize("hasRole('EDITOR')")
    public ResponseEntity<ModelingExercise> importExercise(@PathVariable long sourceExerciseId, @RequestBody ModelingExercise importedExercise) throws URISyntaxException {
        if (sourceExerciseId <= 0 || (importedExercise.getCourseViaExerciseGroupOrCourseMember() == null && importedExercise.getExerciseGroup() == null)) {
            log.debug("Either the courseId or exerciseGroupId must be set for an import");
            throw new BadRequestAlertException("Either the courseId or exerciseGroupId must be set for an import", ENTITY_NAME, "noCourseIdOrExerciseGroupId");
        }
        importedExercise.checkCourseAndExerciseGroupExclusivity("Modeling Exercise");
        final var user = userRepository.getUserWithGroupsAndAuthorities();
        final var originalModelingExercise = modelingExerciseRepository.findByIdWithExampleSubmissionsAndResultsElseThrow(sourceExerciseId);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.EDITOR, importedExercise, user);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.EDITOR, originalModelingExercise, user);
        // validates general settings: points, dates
        importedExercise.validateGeneralSettings();

        final var newModelingExercise = modelingExerciseImportService.importModelingExercise(originalModelingExercise, importedExercise);
        ModelingExercise result = modelingExerciseRepository.save(newModelingExercise);
        modelingExerciseService.scheduleOperations(result.getId());
        return ResponseEntity.created(new URI("/api/modeling-exercises/" + newModelingExercise.getId()))
                .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, newModelingExercise.getId().toString())).body(newModelingExercise);
    }

    /**
     * POST modeling-exercises/:exerciseId/export-submissions : sends exercise submissions as zip
     *
     * @param exerciseId the id of the exercise to get the repos from
     * @param submissionExportOptions the options that should be used for the export
     * @return ResponseEntity with status
     */
    @PostMapping("modeling-exercises/{exerciseId}/export-submissions")
    @PreAuthorize("hasRole('TA')")
    public ResponseEntity<Resource> exportSubmissions(@PathVariable long exerciseId, @RequestBody SubmissionExportOptionsDTO submissionExportOptions) {
        ModelingExercise modelingExercise = modelingExerciseRepository.findByIdElseThrow(exerciseId);

        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.TEACHING_ASSISTANT, modelingExercise, null);

        // TAs are not allowed to download all participations
        if (submissionExportOptions.isExportAllParticipants()) {
            authCheckService.checkHasAtLeastRoleInCourseElseThrow(Role.INSTRUCTOR, modelingExercise.getCourseViaExerciseGroupOrCourseMember(), null);
        }

        File zipFile = modelingSubmissionExportService.exportStudentSubmissionsElseThrow(exerciseId, submissionExportOptions);
        return ResponseUtil.ok(zipFile);
    }

    /**
     * GET modeling-exercises/{exerciseId}/plagiarism-result
     * <p>
     * Return the latest plagiarism result or null, if no plagiarism was detected for this exercise yet.
     *
     * @param exerciseId ID of the modeling exercise for which the plagiarism result should be
     *                   returned
     * @return The ResponseEntity with status 200 (Ok) or with status 400 (Bad Request) if the
     * parameters are invalid
     */
    @GetMapping("modeling-exercises/{exerciseId}/plagiarism-result")
    @PreAuthorize("hasRole('EDITOR')")
    public ResponseEntity<ModelingPlagiarismResult> getPlagiarismResult(@PathVariable long exerciseId) {
        log.debug("REST request to get the latest plagiarism result for the modeling exercise with id: {}", exerciseId);
        ModelingExercise modelingExercise = modelingExerciseRepository.findByIdWithStudentParticipationsSubmissionsResultsElseThrow(exerciseId);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.EDITOR, modelingExercise, null);
        var plagiarismResult = plagiarismResultRepository.findFirstByExerciseIdOrderByLastModifiedDateDescOrNull(modelingExercise.getId());
        return ResponseEntity.ok((ModelingPlagiarismResult) plagiarismResult);
    }

    /**
     * GET modeling-exercises/{exerciseId}/check-plagiarism
     * <p>
     * Start the automated plagiarism detection for the given exercise and return its result.
     *
     * @param exerciseId          for which all submission should be checked
     * @param similarityThreshold ignore comparisons whose similarity is below this threshold (%)
     * @param minimumScore        consider only submissions whose score is greater or equal to this
     *                            value
     * @param minimumSize         consider only submissions whose size is greater or equal to this
     *                            value
     * @return the ResponseEntity with status 200 (OK) and the list of at most 500 pair-wise submissions with a similarity above the given threshold (e.g. 50%).
     */
    @GetMapping("modeling-exercises/{exerciseId}/check-plagiarism")
    @FeatureToggle(Feature.PlagiarismChecks)
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ModelingPlagiarismResult> checkPlagiarism(@PathVariable long exerciseId, @RequestParam float similarityThreshold, @RequestParam int minimumScore,
            @RequestParam int minimumSize) {
        var modelingExercise = modelingExerciseRepository.findByIdWithStudentParticipationsSubmissionsResultsElseThrow(exerciseId);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.INSTRUCTOR, modelingExercise, null);
        long start = System.nanoTime();
        log.info("Start modelingPlagiarismDetectionService.checkPlagiarism for exercise {}", exerciseId);
        ModelingPlagiarismResult result = modelingPlagiarismDetectionService.checkPlagiarism(modelingExercise, similarityThreshold / 100, minimumSize, minimumScore);
        log.info("Finished modelingPlagiarismDetectionService.checkPlagiarism call for {} comparisons in {}", result.getComparisons().size(),
                TimeLogUtil.formatDurationFrom(start));
        result.sortAndLimit(500);
        log.info("Limited number of comparisons to {} to avoid performance issues when saving to database", result.getComparisons().size());
        start = System.nanoTime();
        plagiarismResultRepository.savePlagiarismResultAndRemovePrevious(result);
        log.info("Finished plagiarismResultRepository.savePlagiarismResultAndRemovePrevious call in {}", TimeLogUtil.formatDurationFrom(start));
        return ResponseEntity.ok(result);
    }

    /**
     * PUT modeling-exercises/{exerciseId}/re-evaluate : Re-evaluates and updates an existing modelingExercise.
     *
     * @param exerciseId                                   of the exercise
     * @param modelingExercise                             the modelingExercise to re-evaluate and update
     * @param deleteFeedbackAfterGradingInstructionUpdate  boolean flag that indicates whether the associated feedback should be deleted or not
     *
     * @return the ResponseEntity with status 200 (OK) and with body the updated modelingExercise, or
     * with status 400 (Bad Request) if the modelingExercise is not valid, or with status 409 (Conflict)
     * if given exerciseId is not same as in the object of the request body, or with status 500 (Internal
     * Server Error) if the modelingExercise couldn't be updated
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PutMapping(Endpoints.REEVALUATE_EXERCISE)
    @PreAuthorize("hasRole('EDITOR')")
    public ResponseEntity<ModelingExercise> reEvaluateAndUpdateModelingExercise(@PathVariable long exerciseId, @RequestBody ModelingExercise modelingExercise,
            @RequestParam(value = "deleteFeedback", required = false) Boolean deleteFeedbackAfterGradingInstructionUpdate) throws URISyntaxException {
        log.debug("REST request to re-evaluate ModelingExercise : {}", modelingExercise);

        modelingExerciseRepository.findByIdWithStudentParticipationsSubmissionsResultsElseThrow(exerciseId);

        authCheckService.checkGivenExerciseIdSameForExerciseInRequestBodyElseThrow(exerciseId, modelingExercise);

        var user = userRepository.getUserWithGroupsAndAuthorities();
        // make sure the course actually exists
        var course = courseRepository.findByIdElseThrow(modelingExercise.getCourseViaExerciseGroupOrCourseMember().getId());
        authCheckService.checkHasAtLeastRoleInCourseElseThrow(Role.EDITOR, course, user);

        exerciseService.reEvaluateExercise(modelingExercise, deleteFeedbackAfterGradingInstructionUpdate);

        return updateModelingExercise(modelingExercise, null);
    }

    public static final class Endpoints {

        public static final String ROOT = "api";

        public static final String MODELING_EXERCISES = "modeling-exercises";

        public static final String MODELING_EXERCISE = MODELING_EXERCISES + "/{exerciseId}";

        public static final String REEVALUATE_EXERCISE = MODELING_EXERCISE + "/re-evaluate";

        private Endpoints() {
        }
    }
}
