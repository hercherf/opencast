import * as Yup from 'yup';

/**
 * This File contains all schemas used for validation with yup in the context of events and series
 */

const today = new Date();
today.setHours(0, 0, 0, 0);

// Validation Schema used in new event wizard (each step has its own yup validation object)
export const NewEventSchema = [Yup.object().shape({
    title: Yup.string().required('Required')
}), Yup.object().shape({

}),
    Yup.object().shape({
    uploadAssetsTrack: Yup.array().when('sourceMode', {
        is:  value => value === 'UPLOAD',
        then: Yup.array().test('at-least-one-uploaded', 'at least one uploaded', uploadAssetsTrack => {
            return uploadAssetsTrack.some(asset => !!asset.file)
        })
    }),
    scheduleStartDate: Yup.date().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.date().required('Required')
    }),
    scheduleEndDate: Yup.date().when('sourceMode', {
        is: 'SCHEDULE_MULTIPLE',
        then: Yup.date().required('Required')
    }),
    repeatOn: Yup.array().when('sourceMode', {
        is: 'SCHEDULE_MULTIPLE',
        then: Yup.array().min(1).required('Required')
    }),
    scheduleStartTimeHour: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    }),
    scheduleStartTimeMinutes: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    }),
    scheduleDurationHour: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    }),
    scheduleDurationMinutes: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    }),
    scheduleEndTimeHour: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    }),
    scheduleEndTimeMinutes: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    }),
    location: Yup.string().when('sourceMode', {
        is: value => value === 'SCHEDULE_SINGLE' || value === 'SCHEDULE_MULTIPLE',
        then: Yup.string().required('Required')
    })
}), Yup.object().shape({
    processingWorkflow: Yup.string().required('Required')
})]
