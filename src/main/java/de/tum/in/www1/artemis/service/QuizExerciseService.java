package de.tum.in.www1.artemis.service;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.in.www1.artemis.domain.Result;
import de.tum.in.www1.artemis.domain.participation.StudentParticipation;
import de.tum.in.www1.artemis.domain.quiz.*;
import de.tum.in.www1.artemis.repository.*;
import de.tum.in.www1.artemis.service.scheduled.quiz.QuizScheduleService;

@Service
public class QuizExerciseService {

    private final Logger log = LoggerFactory.getLogger(QuizExerciseService.class);

    private final QuizExerciseRepository quizExerciseRepository;

    private final DragAndDropMappingRepository dragAndDropMappingRepository;

    private final ShortAnswerMappingRepository shortAnswerMappingRepository;

    private final ResultRepository resultRepository;

    private final QuizSubmissionRepository quizSubmissionRepository;

    private final QuizScheduleService quizScheduleService;

    private final QuizStatisticService quizStatisticService;

    public QuizExerciseService(QuizExerciseRepository quizExerciseRepository, DragAndDropMappingRepository dragAndDropMappingRepository, ResultRepository resultRepository,
            ShortAnswerMappingRepository shortAnswerMappingRepository, QuizSubmissionRepository quizSubmissionRepository, QuizScheduleService quizScheduleService,
            QuizStatisticService quizStatisticService) {
        this.quizExerciseRepository = quizExerciseRepository;
        this.dragAndDropMappingRepository = dragAndDropMappingRepository;
        this.shortAnswerMappingRepository = shortAnswerMappingRepository;
        this.resultRepository = resultRepository;
        this.quizSubmissionRepository = quizSubmissionRepository;
        this.quizScheduleService = quizScheduleService;
        this.quizStatisticService = quizStatisticService;
    }

    /**
     * Save the given quizExercise to the database and make sure that objects with references to one another are saved in the correct order to avoid PersistenceExceptions
     *
     * @param quizExercise the quiz exercise to save
     * @return the saved quiz exercise
     */
    public QuizExercise save(QuizExercise quizExercise) {

        quizExercise.setMaxPoints(quizExercise.getOverallQuizPoints());

        // create a quizPointStatistic if it does not yet exist
        if (quizExercise.getQuizPointStatistic() == null) {
            var quizPointStatistic = new QuizPointStatistic();
            quizExercise.setQuizPointStatistic(quizPointStatistic);
            quizPointStatistic.setQuiz(quizExercise);
        }
        // make sure the pointers in the statistics are correct
        quizExercise.recalculatePointCounters();

        // fix references in all questions (step 1/2)
        for (var quizQuestion : quizExercise.getQuizQuestions()) {
            if (quizQuestion instanceof MultipleChoiceQuestion mcQuestion) {
                var quizQuestionStatistic = (MultipleChoiceQuestionStatistic) mcQuestion.getQuizQuestionStatistic();
                if (quizQuestionStatistic == null) {
                    quizQuestionStatistic = new MultipleChoiceQuestionStatistic();
                    mcQuestion.setQuizQuestionStatistic(quizQuestionStatistic);
                    quizQuestionStatistic.setQuizQuestion(mcQuestion);
                }

                for (var answerOption : mcQuestion.getAnswerOptions()) {
                    quizQuestionStatistic.addAnswerOption(answerOption);
                }

                // if an answerOption was removed then remove the associated AnswerCounters implicitly
                Set<AnswerCounter> answerCounterToDelete = new HashSet<>();
                for (AnswerCounter answerCounter : quizQuestionStatistic.getAnswerCounters()) {
                    if (answerCounter.getId() != null) {
                        if (!(mcQuestion.getAnswerOptions().contains(answerCounter.getAnswer()))) {
                            answerCounter.setAnswer(null);
                            answerCounterToDelete.add(answerCounter);
                        }
                    }
                }
                quizQuestionStatistic.getAnswerCounters().removeAll(answerCounterToDelete);
            }
            else if (quizQuestion instanceof DragAndDropQuestion dndQuestion) {
                var quizQuestionStatistic = (DragAndDropQuestionStatistic) dndQuestion.getQuizQuestionStatistic();
                if (quizQuestionStatistic == null) {
                    quizQuestionStatistic = new DragAndDropQuestionStatistic();
                    dndQuestion.setQuizQuestionStatistic(quizQuestionStatistic);
                    quizQuestionStatistic.setQuizQuestion(dndQuestion);
                }

                for (var dropLocation : dndQuestion.getDropLocations()) {
                    quizQuestionStatistic.addDropLocation(dropLocation);
                }

                // if a dropLocation was removed then remove the associated AnswerCounters implicitly
                Set<DropLocationCounter> dropLocationCounterToDelete = new HashSet<>();
                for (DropLocationCounter dropLocationCounter : quizQuestionStatistic.getDropLocationCounters()) {
                    if (dropLocationCounter.getId() != null) {
                        if (!(dndQuestion.getDropLocations().contains(dropLocationCounter.getDropLocation()))) {
                            dropLocationCounter.setDropLocation(null);
                            dropLocationCounterToDelete.add(dropLocationCounter);
                        }
                    }
                }
                quizQuestionStatistic.getDropLocationCounters().removeAll(dropLocationCounterToDelete);

                // save references as index to prevent Hibernate Persistence problem
                saveCorrectMappingsInIndices(dndQuestion);
            }
            else if (quizQuestion instanceof ShortAnswerQuestion saQuestion) {
                var quizQuestionStatistic = (ShortAnswerQuestionStatistic) saQuestion.getQuizQuestionStatistic();
                if (quizQuestionStatistic == null) {
                    quizQuestionStatistic = new ShortAnswerQuestionStatistic();
                    saQuestion.setQuizQuestionStatistic(quizQuestionStatistic);
                    quizQuestionStatistic.setQuizQuestion(quizQuestion);
                }

                for (var spot : saQuestion.getSpots()) {
                    spot.setQuestion(saQuestion);
                    quizQuestionStatistic.addSpot(spot);
                }

                // if a spot was removed then remove the associated spotCounters implicitly
                Set<ShortAnswerSpotCounter> spotCounterToDelete = new HashSet<>();
                for (ShortAnswerSpotCounter spotCounter : quizQuestionStatistic.getShortAnswerSpotCounters()) {
                    if (spotCounter.getId() != null) {
                        if (!(saQuestion.getSpots().contains(spotCounter.getSpot()))) {
                            spotCounter.setSpot(null);
                            spotCounterToDelete.add(spotCounter);
                        }
                    }
                }
                quizQuestionStatistic.getShortAnswerSpotCounters().removeAll(spotCounterToDelete);

                // save references as index to prevent Hibernate Persistence problem
                saveCorrectMappingsInIndicesShortAnswer(saQuestion);
            }
        }

        // Note: save will automatically remove deleted questions from the exercise and deleted answer options from the questions
        // and delete the now orphaned entries from the database
        log.debug("Save quiz to database: {}", quizExercise);
        quizExercise = quizExerciseRepository.saveAndFlush(quizExercise);

        // fix references in all drag and drop questions and short answer questions (step 2/2)
        for (QuizQuestion quizQuestion : quizExercise.getQuizQuestions()) {
            if (quizQuestion instanceof DragAndDropQuestion dragAndDropQuestion) {
                // restore references from index after save
                restoreCorrectMappingsFromIndices(dragAndDropQuestion);
            }
            else if (quizQuestion instanceof ShortAnswerQuestion shortAnswerQuestion) {
                // restore references from index after save
                restoreCorrectMappingsFromIndicesShortAnswer(shortAnswerQuestion);
            }
        }

        if (quizExercise.isCourseExercise()) {
            // only schedule quizzes for course exercises, not for exam exercises
            quizScheduleService.scheduleQuizStart(quizExercise.getId());
        }
        return quizExercise;
    }

    /**
     * adjust existing results if an answer or and question was deleted and recalculate the scores
     *
     * @param quizExercise the changed quizExercise.
     */
    private void updateResultsOnQuizChanges(QuizExercise quizExercise) {
        // change existing results if an answer or and question was deleted
        List<Result> results = resultRepository.findByParticipationExerciseIdOrderByCompletionDateAsc(quizExercise.getId());
        log.debug("Found {} results to update for quiz re-evaluate", results.size());
        List<QuizSubmission> submissions = new ArrayList<>();
        for (Result result : results) {

            Set<SubmittedAnswer> submittedAnswersToDelete = new HashSet<>();
            QuizSubmission quizSubmission = (QuizSubmission) result.getSubmission();

            for (SubmittedAnswer submittedAnswer : quizSubmission.getSubmittedAnswers()) {
                // Delete all references to question and question-elements if the question was changed
                submittedAnswer.checkAndDeleteReferences(quizExercise);
                if (!quizExercise.getQuizQuestions().contains(submittedAnswer.getQuizQuestion())) {
                    submittedAnswersToDelete.add(submittedAnswer);
                }
            }
            quizSubmission.getSubmittedAnswers().removeAll(submittedAnswersToDelete);

            // recalculate existing score
            quizSubmission.calculateAndUpdateScores(quizExercise);
            // update Successful-Flag in Result
            StudentParticipation studentParticipation = (StudentParticipation) result.getParticipation();
            studentParticipation.setExercise(quizExercise);
            result.evaluateSubmission();

            submissions.add(quizSubmission);
        }
        // save the updated submissions and results
        quizSubmissionRepository.saveAll(submissions);
        resultRepository.saveAll(results);
        log.info("{} results have been updated successfully for quiz re-evaluate", results.size());
    }

    /**
     * remove dragItem and dropLocation from correct mappings and set dragItemIndex and dropLocationIndex instead
     *
     * @param dragAndDropQuestion the question for which to perform these actions
     */
    private void saveCorrectMappingsInIndices(DragAndDropQuestion dragAndDropQuestion) {
        List<DragAndDropMapping> mappingsToBeRemoved = new ArrayList<>();
        for (DragAndDropMapping mapping : dragAndDropQuestion.getCorrectMappings()) {
            // check for NullPointers
            if (mapping.getDragItem() == null || mapping.getDropLocation() == null) {
                mappingsToBeRemoved.add(mapping);
                continue;
            }

            // drag item index
            DragItem dragItem = mapping.getDragItem();
            boolean dragItemFound = false;
            for (DragItem questionDragItem : dragAndDropQuestion.getDragItems()) {
                if (dragItem.equals(questionDragItem)) {
                    dragItemFound = true;
                    mapping.setDragItemIndex(dragAndDropQuestion.getDragItems().indexOf(questionDragItem));
                    mapping.setDragItem(null);
                    break;
                }
            }

            // drop location index
            DropLocation dropLocation = mapping.getDropLocation();
            boolean dropLocationFound = false;
            for (DropLocation questionDropLocation : dragAndDropQuestion.getDropLocations()) {
                if (dropLocation.equals(questionDropLocation)) {
                    dropLocationFound = true;
                    mapping.setDropLocationIndex(dragAndDropQuestion.getDropLocations().indexOf(questionDropLocation));
                    mapping.setDropLocation(null);
                    break;
                }
            }

            // if one of them couldn't be found, remove the mapping entirely
            if (!dragItemFound || !dropLocationFound) {
                mappingsToBeRemoved.add(mapping);
            }
        }

        for (DragAndDropMapping mapping : mappingsToBeRemoved) {
            dragAndDropQuestion.removeCorrectMapping(mapping);
        }
    }

    /**
     * remove solutions and spots from correct mappings and set solutionIndex and spotIndex instead
     *
     * @param shortAnswerQuestion the question for which to perform these actions
     */
    private void saveCorrectMappingsInIndicesShortAnswer(ShortAnswerQuestion shortAnswerQuestion) {
        List<ShortAnswerMapping> mappingsToBeRemoved = new ArrayList<>();
        for (ShortAnswerMapping mapping : shortAnswerQuestion.getCorrectMappings()) {
            // check for NullPointers
            if (mapping.getSolution() == null || mapping.getSpot() == null) {
                mappingsToBeRemoved.add(mapping);
                continue;
            }

            // solution index
            ShortAnswerSolution solution = mapping.getSolution();
            boolean solutionFound = false;
            for (ShortAnswerSolution questionSolution : shortAnswerQuestion.getSolutions()) {
                if (solution.equals(questionSolution)) {
                    solutionFound = true;
                    mapping.setShortAnswerSolutionIndex(shortAnswerQuestion.getSolutions().indexOf(questionSolution));
                    mapping.setSolution(null);
                    break;
                }
            }

            // replace spot
            ShortAnswerSpot spot = mapping.getSpot();
            boolean spotFound = false;
            for (ShortAnswerSpot questionSpot : shortAnswerQuestion.getSpots()) {
                if (spot.equals(questionSpot)) {
                    spotFound = true;
                    mapping.setShortAnswerSpotIndex(shortAnswerQuestion.getSpots().indexOf(questionSpot));
                    mapping.setSpot(null);
                    break;
                }
            }

            // if one of them couldn't be found, remove the mapping entirely
            if (!solutionFound || !spotFound) {
                mappingsToBeRemoved.add(mapping);
            }
        }

        for (ShortAnswerMapping mapping : mappingsToBeRemoved) {
            shortAnswerQuestion.removeCorrectMapping(mapping);
        }
    }

    /**
     * restore dragItem and dropLocation for correct mappings using dragItemIndex and dropLocationIndex
     *
     * @param dragAndDropQuestion the question for which to perform these actions
     */
    private void restoreCorrectMappingsFromIndices(DragAndDropQuestion dragAndDropQuestion) {
        for (DragAndDropMapping mapping : dragAndDropQuestion.getCorrectMappings()) {
            // drag item
            mapping.setDragItem(dragAndDropQuestion.getDragItems().get(mapping.getDragItemIndex()));
            // drop location
            mapping.setDropLocation(dragAndDropQuestion.getDropLocations().get(mapping.getDropLocationIndex()));
            // set question
            mapping.setQuestion(dragAndDropQuestion);
            // save mapping
            dragAndDropMappingRepository.save(mapping);
        }
    }

    /**
     * restore solution and spots for correct mappings using solutionIndex and spotIndex
     *
     * @param shortAnswerQuestion the question for which to perform these actions
     */
    private void restoreCorrectMappingsFromIndicesShortAnswer(ShortAnswerQuestion shortAnswerQuestion) {
        for (ShortAnswerMapping mapping : shortAnswerQuestion.getCorrectMappings()) {
            // solution
            mapping.setSolution(shortAnswerQuestion.getSolutions().get(mapping.getShortAnswerSolutionIndex()));
            // spot
            mapping.setSpot(shortAnswerQuestion.getSpots().get(mapping.getShortAnswerSpotIndex()));
            // set question
            mapping.setQuestion(shortAnswerQuestion);
            // save mapping
            shortAnswerMappingRepository.save(mapping);
        }
    }

    /**
     *
     * @param quizExercise the changed quiz exercise from the client
     * @param originalQuizExercise the original quiz exercise (with statistics)
     * @return the updated quiz exercise with the changed statistics
     */
    public QuizExercise reEvaluate(QuizExercise quizExercise, QuizExercise originalQuizExercise) {

        quizExercise.undoUnallowedChanges(originalQuizExercise);
        boolean updateOfResultsAndStatisticsNecessary = quizExercise.checkIfRecalculationIsNecessary(originalQuizExercise);

        // update QuizExercise
        quizExercise.setMaxPoints(quizExercise.getOverallQuizPoints());
        quizExercise.reconnectJSONIgnoreAttributes();

        // adjust existing results if an answer or a question was deleted and recalculate them
        updateResultsOnQuizChanges(quizExercise);

        quizExercise = save(quizExercise);

        if (updateOfResultsAndStatisticsNecessary) {
            // make sure we have all objects available before updating the statistics to avoid lazy / proxy issues
            quizExercise = quizExerciseRepository.findByIdWithQuestionsAndStatisticsElseThrow(quizExercise.getId());
            quizStatisticService.recalculateStatistics(quizExercise);
        }
        // fetch the quiz exercise again to make sure the latest changes are included
        return quizExerciseRepository.findByIdWithQuestionsAndStatisticsElseThrow(quizExercise.getId());
    }

    /**
     * Reset a QuizExercise to its original state, delete statistics and cleanup the schedule service.
     * @param exerciseId id of the exercise to reset
     */
    public void resetExercise(Long exerciseId) {
        // fetch exercise again to make sure we have an updated version
        QuizExercise quizExercise = quizExerciseRepository.findByIdWithQuestionsAndStatisticsElseThrow(exerciseId);

        // for quizzes, we need to delete the statistics, and we need to reset the quiz to its original state
        quizExercise.setIsVisibleBeforeStart(Boolean.FALSE);
        quizExercise.setIsPlannedToStart(Boolean.FALSE);
        quizExercise.setAllowedNumberOfAttempts(null);
        quizExercise.setIsOpenForPractice(Boolean.FALSE);
        quizExercise.setReleaseDate(null);

        quizExercise = save(quizExercise);

        // in case the quiz has not yet started or the quiz is currently running, we have to clean up
        quizScheduleService.cancelScheduledQuizStart(quizExercise.getId());
        quizScheduleService.clearQuizData(quizExercise.getId());

        // clean up the statistics
        quizStatisticService.recalculateStatistics(quizExercise);
    }

    public void cancelScheduledQuiz(Long quizExerciseId) {
        quizScheduleService.cancelScheduledQuizStart(quizExerciseId);
        quizScheduleService.clearQuizData(quizExerciseId);
    }
}
