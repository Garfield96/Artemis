import { TestBed } from '@angular/core/testing';
import { ComplaintService } from 'app/complaints/complaint.service';
import { Complaint, ComplaintType } from 'app/entities/complaint.model';
import { TextExercise } from 'app/entities/text-exercise.model';
import { ComplaintResponse } from 'app/entities/complaint-response.model';
import { User } from 'app/core/user/user.model';
import { AccountService } from 'app/core/auth/account.service';
import { MockAccountService } from '../../helpers/mocks/service/mock-account.service';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import dayjs from 'dayjs/esm';
import { Result } from 'app/entities/result.model';

describe('ComplaintService', () => {
    let complaintService: ComplaintService;
    let accountService: AccountService;
    let httpMock: HttpTestingController;

    const dayjsTime1 = dayjs().utc().year(2022).month(3).date(14).hour(10).minute(35).second(12).millisecond(332);
    const stringTime1 = '2022-04-14T10:35:12.332Z';
    const dayjsTime2 = dayjs().utc().year(2022).month(4).date(12).hour(18).minute(12).second(11).millisecond(140);
    const stringTime2 = '2022-05-12T18:12:11.140Z';

    const clientComplaint1 = new Complaint();
    clientComplaint1.id = 42;
    clientComplaint1.complaintType = ComplaintType.MORE_FEEDBACK;
    clientComplaint1.result = new Result();
    clientComplaint1.submittedTime = dayjsTime1;
    clientComplaint1.complaintText = 'Test text';

    const serverComplaint1 = { ...clientComplaint1, submittedTime: stringTime1 };

    const clientComplaint2 = new Complaint();
    clientComplaint2.id = 42;
    clientComplaint2.complaintType = ComplaintType.MORE_FEEDBACK;
    clientComplaint2.result = new Result();
    clientComplaint2.submittedTime = dayjsTime2;
    clientComplaint2.complaintText = 'Another test text';

    const serverComplaint2 = { ...clientComplaint2, submittedTime: stringTime2 };

    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [HttpClientTestingModule],
            providers: [{ provide: AccountService, useClass: MockAccountService }],
        })
            .compileComponents()
            .then(() => {
                complaintService = TestBed.inject(ComplaintService);
                accountService = TestBed.inject(AccountService);
                httpMock = TestBed.inject(HttpTestingController);
            });
    });

    afterEach(() => {
        httpMock.verify();
    });

    describe('isComplaintLockedForLoggedInUser', () => {
        it('should be false if no visible lock is present', () => {
            const result = complaintService.isComplaintLockedForLoggedInUser(new Complaint(), new TextExercise(undefined, undefined));
            expect(result).toBe(false);
        });

        it('should be false if user has the lock', () => {
            const login = 'testUser';
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.reviewer = { login } as User;
            _complaint.complaintResponse.isCurrentlyLocked = true;
            accountService.userIdentity = { login } as User;

            const result = complaintService.isComplaintLockedForLoggedInUser(_complaint, new TextExercise(undefined, undefined));

            expect(result).toBe(false);
        });

        it('should be true if user has not the lock', () => {
            const login = 'testUser';
            const anotherLogin = 'anotherTestUser';
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.reviewer = { login } as User;
            _complaint.complaintResponse.isCurrentlyLocked = true;
            accountService.userIdentity = { login: anotherLogin } as User;
            jest.spyOn(accountService, 'isAtLeastInstructorForExercise').mockReturnValue(false);

            const result = complaintService.isComplaintLockedForLoggedInUser(_complaint, new TextExercise(undefined, undefined));

            expect(result).toBe(true);
        });

        it('should be false if user is instructor', () => {
            const login = 'testUser';
            const anotherLogin = 'anotherTestUser';
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.reviewer = { login } as User;
            _complaint.complaintResponse.isCurrentlyLocked = true;
            accountService.userIdentity = { login: anotherLogin } as User;
            jest.spyOn(accountService, 'isAtLeastInstructorForExercise').mockReturnValue(true);

            const result = complaintService.isComplaintLockedForLoggedInUser(_complaint, new TextExercise(undefined, undefined));

            expect(result).toBe(false);
        });
    });

    describe('isComplaintLockedByLoggedInUser', () => {
        it('should be false if no visible lock is present', () => {
            const result = complaintService.isComplaintLockedByLoggedInUser(new Complaint());
            expect(result).toBe(false);
        });

        it('should be false if the complaint is not locked', () => {
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();

            const result = complaintService.isComplaintLockedByLoggedInUser(_complaint);
            expect(result).toBe(false);
        });

        it('should be false if the complaint has been handled', () => {
            const _complaint = new Complaint();
            _complaint.accepted = true;
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.isCurrentlyLocked = true;
            _complaint.complaintResponse.submittedTime = dayjs();

            const result = complaintService.isComplaintLockedByLoggedInUser(_complaint);
            expect(result).toBe(false);
        });

        it('should be false if another user has the lock', () => {
            const login = 'testUser';
            const anotherLogin = 'anotherTestUser';
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.isCurrentlyLocked = true;
            _complaint.complaintResponse.reviewer = { login } as User;
            accountService.userIdentity = { login: anotherLogin } as User;

            const result = complaintService.isComplaintLockedByLoggedInUser(_complaint);
            expect(result).toBe(false);
        });

        it('should be true if the same user has the lock', () => {
            const login = 'testUser';
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.isCurrentlyLocked = true;
            _complaint.complaintResponse.reviewer = { login } as User;
            accountService.userIdentity = { login } as User;

            const result = complaintService.isComplaintLockedByLoggedInUser(_complaint);
            expect(result).toBe(true);
        });
    });

    describe('isComplaintLocked', () => {
        it('should be false if no visible lock is present', () => {
            const result = complaintService.isComplaintLocked(new Complaint());
            expect(result).toBe(false);
        });

        it('should be true if locked', () => {
            const _complaint = new Complaint();
            _complaint.complaintResponse = new ComplaintResponse();
            _complaint.complaintResponse.isCurrentlyLocked = true;

            const result = complaintService.isComplaintLocked(_complaint);
            expect(result).toBe(true);
        });
    });

    describe('create', () => {
        it('For course', () => {
            complaintService.create(clientComplaint1).subscribe((received) => {
                expect(received).toEqual(clientComplaint1);
            });

            const res = httpMock.expectOne({ method: 'POST' });
            expect(res.request.url).toBe('api/complaints');
            expect(res.request.body).toEqual(serverComplaint1);

            res.flush(clone(serverComplaint1));
        });

        it('For exam', () => {
            const examId = 1337;

            complaintService.create(clientComplaint1, examId).subscribe((received) => {
                expect(received).toEqual(clientComplaint1);
            });

            const res = httpMock.expectOne({ method: 'POST' });
            expect(res.request.url).toBe(`api/complaints/exam/${examId}`);
            expect(res.request.body).toEqual(serverComplaint1);

            res.flush(clone(serverComplaint1));
        });
    });

    it('findBySubmissionId', () => {
        const submissionId = 1337;
        complaintService.findBySubmissionId(submissionId).subscribe((received) => {
            expect(received).toEqual(clientComplaint1);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/complaints/submissions/${submissionId}`);

        res.flush(clone(serverComplaint1));
    });

    it('getComplaintsForTestRun', () => {
        const exerciseId = 1337;
        complaintService.getComplaintsForTestRun(exerciseId).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/exercises/${exerciseId}/complaints-for-test-run-dashboard`);

        res.flush([clone(serverComplaint1), clone(serverComplaint2)]);
    });

    it('getMoreFeedbackRequestsForTutor', () => {
        const exerciseId = 1337;
        complaintService.getMoreFeedbackRequestsForTutor(exerciseId).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/exercises/${exerciseId}/more-feedback-for-assessment-dashboard`);

        res.flush([clone(serverComplaint1), clone(serverComplaint2)]);
    });

    it('getNumberOfAllowedComplaintsInCourse', () => {
        const courseId = 42;
        const teamMode = true;
        const expectedCount = 69;

        complaintService.getNumberOfAllowedComplaintsInCourse(courseId, teamMode).subscribe((received) => {
            expect(received).toBe(expectedCount);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/courses/${courseId}/allowed-complaints?teamMode=${teamMode}`);

        res.flush(expectedCount);
    });

    it('findAllByTutorIdForCourseId', () => {
        const tutorId = 1337;
        const courseId = 69;
        const complaintType = ComplaintType.COMPLAINT;

        complaintService.findAllByTutorIdForCourseId(tutorId, courseId, complaintType).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/courses/${courseId}/complaints?complaintType=${complaintType}&tutorId=${tutorId}`);

        res.flush([clone(clientComplaint1), clone(clientComplaint2)]);
    });

    it('findAllByTutorIdForExerciseId', () => {
        const tutorId = 1337;
        const exerciseId = 69;
        const complaintType = ComplaintType.COMPLAINT;

        complaintService.findAllByTutorIdForExerciseId(tutorId, exerciseId, complaintType).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/exercises/${exerciseId}/complaints?complaintType=${complaintType}&tutorId=${tutorId}`);

        res.flush([clone(clientComplaint1), clone(clientComplaint2)]);
    });

    it('findAllByCourseId', () => {
        const courseId = 69;
        const complaintType = ComplaintType.COMPLAINT;

        complaintService.findAllByCourseId(courseId, complaintType).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/courses/${courseId}/complaints?complaintType=${complaintType}`);

        res.flush([clone(clientComplaint1), clone(clientComplaint2)]);
    });

    it('findAllByCourseIdAndExamId', () => {
        const courseId = 69;
        const examId = 42;

        complaintService.findAllByCourseIdAndExamId(courseId, examId).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/courses/${courseId}/exams/${examId}/complaints`);

        res.flush([clone(clientComplaint1), clone(clientComplaint2)]);
    });

    it('findAllByExerciseId', () => {
        const exerciseId = 69;
        const complaintType = ComplaintType.COMPLAINT;

        complaintService.findAllByExerciseId(exerciseId, complaintType).subscribe((received) => {
            expect(received).toIncludeSameMembers([clientComplaint1, clientComplaint2]);
        });

        const res = httpMock.expectOne({ method: 'GET' });
        expect(res.request.url).toBe(`api/exercises/${exerciseId}/complaints?complaintType=${complaintType}`);

        res.flush([clone(clientComplaint1), clone(clientComplaint2)]);
    });

    function clone(object: object): object {
        return Object.assign({}, object);
    }
});