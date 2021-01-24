package de.tum.in.www1.artemis.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import de.tum.in.www1.artemis.domain.Exercise;
import de.tum.in.www1.artemis.domain.Result;
import de.tum.in.www1.artemis.domain.User;
import de.tum.in.www1.artemis.domain.participation.Participation;
import de.tum.in.www1.artemis.domain.participation.ProgrammingExerciseStudentParticipation;
import de.tum.in.www1.artemis.domain.participation.StudentParticipation;
import de.tum.in.www1.artemis.domain.scores.StudentScore;
import de.tum.in.www1.artemis.repository.ExerciseRepository;
import de.tum.in.www1.artemis.repository.StudentParticipationRepository;
import de.tum.in.www1.artemis.repository.StudentScoreRepository;

@Service
public class StudentScoreService {

    private final Logger log = LoggerFactory.getLogger(StudentScoreService.class);

    private final StudentScoreRepository studentScoreRepository;

    private final StudentParticipationRepository studentParticipationRepository;

    private final ExerciseRepository exerciseRepository;

    public StudentScoreService(StudentScoreRepository studentScoreRepository, StudentParticipationRepository studentParticipationRepository,
            ExerciseRepository exerciseRepository) {
        this.studentScoreRepository = studentScoreRepository;
        this.studentParticipationRepository = studentParticipationRepository;
        this.exerciseRepository = exerciseRepository;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void removeAssociatedStudentScores(Exercise exercise) {
        studentScoreRepository.removeAssociatedWithExercise(exercise.getId());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void removeAssociatedStudentScores(Result resultToBeDeleted) {
        Optional<StudentScore> associatedStudentScoreOptional = studentScoreRepository.findStudentScoreAssociatedWithResult(resultToBeDeleted.getId());
        if (associatedStudentScoreOptional.isEmpty()) {
            return;
        }
        StudentScore associatedStudentScore = associatedStudentScoreOptional.get();

        List<Result> resultOrdered = exerciseRepository
                .getResultsOrderedByParticipationIdSubmissionIdResultIdDesc(associatedStudentScore.getExercise().getId(), associatedStudentScore.getUser().getId()).stream()
                .filter(r -> !resultToBeDeleted.equals(r)).collect(Collectors.toList());
        List<Result> ratedResultOrdered = exerciseRepository
                .getRatedResultsOrderedByParticipationIdSubmissionIdResultIdDesc(associatedStudentScore.getExercise().getId(), associatedStudentScore.getUser().getId()).stream()
                .filter(r -> !resultToBeDeleted.equals(r)).collect(Collectors.toList());

        if (resultToBeDeleted.equals(associatedStudentScore.getLastRatedResult())) {
            if (!ratedResultOrdered.isEmpty()) {
                Result newLastRatedResult = ratedResultOrdered.get(0);
                associatedStudentScore.setLastRatedResult(newLastRatedResult);
                associatedStudentScore.setLastRatedScore(newLastRatedResult.getScore());
            }
            else {
                associatedStudentScore.setLastRatedResult(null);
                associatedStudentScore.setLastRatedScore(null);
            }
        }

        if (resultToBeDeleted.equals(associatedStudentScore.getLastResult())) {
            if (!resultOrdered.isEmpty()) {
                Result newLastResult = resultOrdered.get(0);
                associatedStudentScore.setLastResult(newLastResult);
                associatedStudentScore.setLastScore(newLastResult.getScore());
            }
            else {
                associatedStudentScore.setLastResult(null);
                associatedStudentScore.setLastScore(null);
            }
        }
        if (associatedStudentScore.getLastResult() == null && associatedStudentScore.getLastRatedResult() == null) {
            studentScoreRepository.deleteById(associatedStudentScore.getId());
        }
        else {
            studentScoreRepository.saveAndFlush(associatedStudentScore);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void updateStudentScores(Result result) {
        // results without a score are uninteresting, we can eliminate them directly
        if (result.getScore() == null || result.getCompletionDate() == null) {
            return;
        }
        // load the corresponding student participation for information about student and exercise
        Optional<StudentParticipation> participationOptional = studentParticipationRepository.findStudentParticipationAssociatedWithResult(result.getId());
        if (participationOptional.isEmpty()) {
            return;
        }
        Participation participation = participationOptional.get();
        boolean isInstanceOfStudentParticipation = participation.getClass().equals(StudentParticipation.class)
                || participation.getClass().equals(ProgrammingExerciseStudentParticipation.class);
        if (!isInstanceOfStudentParticipation) {
            return;
        }
        StudentParticipation studentParticipation = (StudentParticipation) participation;

        Exercise exercise = studentParticipation.getExercise();
        // team exercises are uninteresting
        if (exercise.isTeamMode()) {
            return;
        }
        User user = studentParticipation.getStudent().get();

        // Todo: Maybe do the db query here that the incoming result is the last result for the given exercise and student
        Optional<StudentScore> studentScoreOptional = studentScoreRepository.findStudentScoreByStudentAndExercise(exercise.getId(), user.getId());

        // there exists already a student score for this exercise and student -> might need to update it
        if (studentScoreOptional.isPresent()) {
            StudentScore existingStudentScore = studentScoreOptional.get();

            // update the last result and last score if either it has not been set previously or new result is either the old one (=) or newer (>)
            if (existingStudentScore.getLastResult() == null || result.getId() >= existingStudentScore.getLastResult().getId()) {
                existingStudentScore.setLastResult(result);
                existingStudentScore.setLastScore(result.getScore());
                existingStudentScore = studentScoreRepository.saveAndFlush(existingStudentScore);
            }
            // update the last rated result and last rated score if either it has not been set previously or new rated result is either the old one (=) or newer (>)
            if ((result.isRated() != null && result.isRated())
                    && (existingStudentScore.getLastRatedResult() == null || result.getId() >= existingStudentScore.getLastRatedResult().getId())) {
                existingStudentScore.setLastRatedResult(result);
                existingStudentScore.setLastRatedScore(result.getScore());
                existingStudentScore = studentScoreRepository.saveAndFlush(existingStudentScore);
            }

            // Edge Case: if the result is now unrated but is equal to the current last rated result we have to set these to null (result was switched from rated to unrated)
            if ((result.isRated() == null || !result.isRated()) && result.equals(existingStudentScore.getLastRatedResult())) {
                existingStudentScore.setLastRatedResult(null);
                existingStudentScore.setLastRatedScore(null);
                studentScoreRepository.saveAndFlush(existingStudentScore);
            }

        }
        else { // no student score exists yet for exercise and student
            StudentScore newStudentScore = new StudentScore();
            newStudentScore.setExercise(exercise);
            newStudentScore.setUser(user);
            newStudentScore.setLastScore(result.getScore());
            newStudentScore.setLastResult(result);
            if (result.isRated() != null && result.isRated()) {
                newStudentScore.setLastRatedScore(result.getScore());
                newStudentScore.setLastRatedResult(result);
            }
            studentScoreRepository.saveAndFlush(newStudentScore);
        }
    }

}
