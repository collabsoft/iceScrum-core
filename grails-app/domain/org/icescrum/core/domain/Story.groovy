/*
 * Copyright (c) 2014 Kagilum SAS.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Nicolas Noullet (nnoullet@kagilum.com)
 * Colin Bontemps (cbontemps@kagilum.com)
 */


package org.icescrum.core.domain

import grails.util.Holders
import groovy.time.TimeCategory
import org.grails.comments.Comment
import org.hibernate.ObjectNotFoundException
import org.icescrum.core.domain.AcceptanceTest.AcceptanceTestState
import org.icescrum.core.utils.DateUtils

class Story extends BacklogElement implements Cloneable, Serializable {

    static final long serialVersionUID = -6800252507987149001L

    static final int STATE_FROZEN = -1
    static final int STATE_SUGGESTED = 1
    static final int STATE_ACCEPTED = 2
    static final int STATE_ESTIMATED = 3
    static final int STATE_PLANNED = 4
    static final int STATE_INPROGRESS = 5
    static final int STATE_INREVIEW = 6
    static final int STATE_DONE = 7
    static final int TYPE_USER_STORY = 0
    static final int TYPE_DEFECT = 2
    static final int TYPE_TECHNICAL_STORY = 3

    int type = 0
    Date frozenDate
    Date suggestedDate
    Date acceptedDate
    Date plannedDate
    Date estimatedDate
    Date inProgressDate
    Date inReviewDate
    Date doneDate
    String origin
    BigDecimal effort = null
    int rank = 0
    int state = Story.STATE_SUGGESTED
    int value = 0
    String affectVersion

    static belongsTo = [
            creator     : User,
            feature     : Feature,
            parentSprint: Sprint,
            dependsOn   : Story
    ]

    static hasMany = [
            tasks          : Task,
            voters         : User,
            followers      : User,
            actors         : Actor,
            dependences    : Story,
            acceptanceTests: AcceptanceTest
    ]

    static transients = [
            'deliveredVersion', 'testState', 'testStateEnum', 'activity', 'sameBacklogStories', 'countDoneTasks', 'project', 'totalRemainingTime'
    ]

    static mapping = {
        cache true
        table 'is_story'
        followers cache: true
        voters cache: true
        tasks cache: true, cascade: 'all', batchSize: 25
        dependences cache: true, sort: "state", order: "asc"
        acceptanceTests sort: 'rank', batchSize: 10, cache: true
        effort precision: 5, scale: 2
        metaDatas cascade: 'delete-orphan', batchSize: 10, cache: true // Doesn't work on BacklogElement
        activities cascade: 'delete-orphan', batchSize: 25, cache: true // Doesn't work on BacklogElement
        actors cache: true
    }

    static constraints = {
        name(unique: 'backlog')
        frozenDate(nullable: true)
        suggestedDate(nullable: true)
        acceptedDate(nullable: true)
        estimatedDate(nullable: true)
        plannedDate(nullable: true)
        inProgressDate(nullable: true)
        inReviewDate(nullable: true)
        doneDate(nullable: true)
        parentSprint(nullable: true, validator: { newSprint, story -> newSprint == null || newSprint.parentProject.id == story.backlog.id ?: 'invalid' })
        feature(nullable: true, validator: { newFeature, story ->
            if (newFeature != null) {
                if (newFeature.backlog.id != story.backlog.id) {
                    return 'invalid'
                } else if (newFeature.state == Feature.STATE_DONE && story.state != STATE_DONE) {
                    return 'is.story.error.feature'
                }
            }
        })
        actors(nullable: true)
        affectVersion(nullable: true)
        effort(nullable: true, validator: { newEffort, story -> newEffort == null || (newEffort >= 0 && newEffort < 1000) ?: 'invalid' })
        creator(nullable: true) // in case of a user deletion, the story can remain without owner
        dependsOn(nullable: true, validator: { newDependsOn, story ->
            newDependsOn == null ||                                                                                   // I depend on nothing or I depend on
            newDependsOn.state >= story.state ||                                                                      // - a story with higher or equal state
            newDependsOn.state == STATE_ACCEPTED && story.state == STATE_ESTIMATED ||                                 // - a story with lower state but I am ESTIMATED and it is ACCEPTED (= in backlog)
            (newDependsOn.state in [STATE_INPROGRESS, STATE_INREVIEW]) && story.state in [STATE_INREVIEW, STATE_DONE] // - a story with lower state but I am IN REVIEW or DONE and it is IN PROGRESS or IN REVIEW (= in sprint)
                    ?: 'invalid'
        })
        origin(nullable: true)
    }

    def getActivity() {
        def activities = this.activities + this.tasks*.activities.flatten() + this.acceptanceTests*.activities.flatten()
        return activities.sort { a, b -> b.dateCreated <=> a.dateCreated }
    }

    def getDeliveredVersion() {
        return this.state == STATE_DONE ? this.parentSprint.deliveredVersion ?: null : null
    }

    def getCountDoneTasks() {
        return tasks.count { it.state == Task.STATE_DONE }
    }

    BigDecimal getTotalRemainingTime() {
        (BigDecimal) tasks?.sum { Task task -> task.estimation ? task.estimation.toBigDecimal() : 0.0 } ?: 0.0
    }

    Map getProject() { // Hack because by default it does not return the asShort but a timebox instead
        Project project = (Project) backlog
        return project ? [class: 'Project', id: project.id, pkey: project.pkey, name: project.name] : [:]
    }

    String getPermalink() {
        return Holders.grailsApplication.config.icescrum.serverURL + '/p/' + backlog.pkey + '-' + this.uid
    }

    List<Story> getSameBacklogStories() {
        def stories
        if (state in [STATE_ACCEPTED, STATE_ESTIMATED]) {
            stories = backlog.stories.findAll {
                it.state in [STATE_ACCEPTED, STATE_ESTIMATED]
            }
        } else if (state > STATE_ESTIMATED) {
            stories = parentSprint?.stories
        } else {
            stories = backlog.stories.findAll {
                it.state == state
            }
        }
        return stories ? stories.asList().collect { get(it.id) }.sort { it.rank } : [] // Force get real entity because otherwise list membership test fails
    }

    int getTestState() {
        getTestStateEnum().id
    }

    static namedQueries = {

        storiesByRelease { r ->
            parentSprint {
                parentRelease {
                    eq 'id', r.id
                }
            }
        }

        findPossiblesDependences { Story story, String term ->
            backlog {
                eq 'id', story.backlog.id
            }
            if (term) {
                if (term.isInteger()) {
                    eq 'uid', term.toInteger()
                } else {
                    ilike 'name', '%' + term + '%'
                }
            }
            or {
                if (story.state == Story.STATE_SUGGESTED) {
                    and {
                        eq 'state', Story.STATE_SUGGESTED
                        lt 'rank', story.rank
                    }
                    and {
                        gt 'state', Story.STATE_SUGGESTED
                    }
                } else if (story.state in [Story.STATE_ACCEPTED, Story.STATE_ESTIMATED]) {
                    and {
                        'in' 'state', [Story.STATE_ACCEPTED, Story.STATE_ESTIMATED]
                        lt 'rank', story.rank
                    }
                    and {
                        gt 'state', Story.STATE_ESTIMATED
                    }
                } else if (story.state in [Story.STATE_PLANNED, Story.STATE_INPROGRESS, Story.STATE_INREVIEW]) {
                    and {
                        'in' 'state', [Story.STATE_PLANNED, Story.STATE_INPROGRESS, Story.STATE_INREVIEW, Story.STATE_DONE]
                        lt 'rank', story.rank
                        parentSprint {
                            eq 'id', story.parentSprint.id
                        }
                    }
                    and {
                        parentSprint {
                            lt 'startDate', story.parentSprint.startDate
                        }
                    }
                } else if (story.state == Story.STATE_DONE) {
                    and {
                        eq 'state', Story.STATE_DONE
                        lt 'rank', story.rank
                        parentSprint {
                            eq 'id', story.parentSprint.id
                        }
                    }
                    and {
                        parentSprint {
                            lt 'startDate', story.parentSprint.startDate
                        }
                    }
                }
            }
            order('feature', 'desc')
            order('state', 'asc')
            order('rank', 'asc')
        }

        findAllByReleaseAndFeature { Release r, Feature f ->
            parentSprint {
                parentRelease {
                    eq 'id', r.id
                }
            }
            feature {
                eq 'id', f.id
            }
        }

        // Return the total number of points in the backlog
        totalPoint { idProject ->
            projections {
                sum 'effort'
                backlog {
                    eq 'id', idProject
                }
                isNull 'parentSprint'
                isNull 'effort'
            }
        }

        getInProject { p, id ->
            backlog {
                eq 'id', p
            }
            and {
                eq 'id', id
            }
            uniqueResult = true
        }
    }

    static Story withStory(long projectId, long id) {
        Story story = (Story) getInProject(projectId, id).list()
        if (!story) {
            throw new ObjectNotFoundException(id, 'Story')
        }
        return story
    }

    static List<Story> withStories(def params, def id = 'id') {
        def ids = params[id]?.contains(',') ? params[id].split(',')*.toLong() : params.list(id)
        List<Story> stories = ids ? getAll(ids).findAll { it && it.backlog.id == params.project.toLong() } : null
        if (!stories) {
            throw new ObjectNotFoundException(ids, 'Story')
        }
        return stories
    }

    static int findNextUId(Long pid) {
        (executeQuery(
                """SELECT MAX(s.uid)
                   FROM org.icescrum.core.domain.Story as s, org.icescrum.core.domain.Project as p
                   WHERE s.backlog = p
                   AND p.id = :pid """, [pid: pid], [readOnly: true])[0] ?: 0) + 1
    }

    static List<Comment> recentCommentsInProject(long projectId) {
        return executeQuery(""" 
                SELECT commentLink.comment 
                FROM Story story, CommentLink as commentLink 
                WHERE story.backlog.id = :projectId 
                AND commentLink.commentRef = story.id 
                AND commentLink.type = 'story' 
                ORDER BY commentLink.comment.dateCreated DESC""", [projectId: projectId], [max: 10, offset: 0, cache: true, readOnly: true]
        )
    }

    static List<Map> storyDates(long projectId, Date storyMinDoneDate) {
        return executeQuery(""" 
            SELECT story.frozenDate, story.suggestedDate, story.acceptedDate, story.estimatedDate, story.plannedDate, story.inProgressDate, story.inReviewDate, story.doneDate
            FROM Story story
            WHERE story.backlog.id = :projectId
            AND story.state = :storyStateDone
            AND story.doneDate > :storyMinDoneDate""", [projectId: projectId, storyStateDone: STATE_DONE, storyMinDoneDate: storyMinDoneDate], [cache: true, readOnly: true]
        ).collect { storyArray ->
            return [
                    (STATE_FROZEN)    : DateUtils.timestampToDate(storyArray[0]),
                    (STATE_SUGGESTED) : DateUtils.timestampToDate(storyArray[1]),
                    (STATE_ACCEPTED)  : DateUtils.timestampToDate(storyArray[2]),
                    (STATE_ESTIMATED) : DateUtils.timestampToDate(storyArray[3]),
                    (STATE_PLANNED)   : DateUtils.timestampToDate(storyArray[4]),
                    (STATE_INPROGRESS): DateUtils.timestampToDate(storyArray[5]),
                    (STATE_INREVIEW)  : DateUtils.timestampToDate(storyArray[6]),
                    (STATE_DONE)      : DateUtils.timestampToDate(storyArray[7])
            ]
        }
    }

    static Integer throughput(long projectId) {
        def releaseInProgressDate = executeQuery(""" 
                SELECT release.inProgressDate
                FROM Release release
                WHERE release.parentProject.id = :projectId
                AND release.state = :releaseStateInProgress""", [projectId: projectId, releaseStateInProgress: Release.STATE_INPROGRESS], [cache: true, readOnly: true]
        )[0]
        if (releaseInProgressDate) {
            def today = new Date()
            def nbTotalDays = today - new Date(releaseInProgressDate.time)
            def nbMaxDays = 4 * 7 // Max 4 weeks (must be a just number of weeks)
            if (nbTotalDays > nbMaxDays) {
                nbTotalDays = nbMaxDays
            }
            def storyMinDoneDate = today - nbTotalDays
            def nbDoneStories = executeQuery(""" 
                SELECT count(*)
                FROM Story story
                WHERE story.backlog.id = :projectId
                AND story.state = :storyStateDone
                AND story.doneDate > :storyMinDoneDate""", [projectId: projectId, storyStateDone: STATE_DONE, storyMinDoneDate: storyMinDoneDate], [cache: true, readOnly: true]
            )[0]
            return nbDoneStories ? Math.round(new BigDecimal(nbDoneStories) * 7 / nbTotalDays) : null
        } else {
            return null
        }
    }

    static Integer meanCycleTime(long projectId, Date storyMinDoneDate) {
        def dates = executeQuery(""" 
                SELECT story.doneDate, min(task.inProgressDate)
                FROM Story story
                INNER JOIN story.tasks task
                WHERE story.backlog.id = :projectId
                AND story.state = :storyStateDone
                AND story.doneDate > :storyMinDoneDate
                GROUP BY story.id""", [projectId: projectId, storyStateDone: STATE_DONE, storyMinDoneDate: storyMinDoneDate], [cache: true, readOnly: true]
        )
        if (dates) {
            dates = dates.findAll { storyDate ->
                storyDate[0] != null && storyDate[1] != null // Some tasks are Done without an inProgressDate, probably due to a bug, so we need to filter out the corresponding stories
            }
            BigDecimal mean = dates.collect { storyDate ->
                Date doneDate = DateUtils.timestampToDate(storyDate[0])
                Date inProgressDate = DateUtils.timestampToDate(storyDate[1])
                new BigDecimal(TimeCategory.minus(doneDate, inProgressDate).days)
            }.sum() / dates.size()
            return Math.round(mean)
        } else {
            return null
        }
    }

    static Date getLastDoneDate(long projectId) { // Cannot be done in subqueries, date arithmetics are not supported in HQL
        def lastDoneDate = executeQuery(""" 
            SELECT MAX(story.doneDate)
            FROM Story story
            WHERE story.backlog.id = :projectId""", [projectId: projectId], [cache: true, readOnly: true]
        )[0]
        return lastDoneDate ? new Date(lastDoneDate.time) : null
    }

    int compareTo(Story o) {
        return rank.compareTo(o.rank)
    }

    @Override
    int hashCode() {
        final Integer prime = 31
        int result = 1
        result = prime * result + ((!effort) ? 0 : effort.hashCode())
        result = prime * result + ((!name) ? 0 : name.hashCode())
        result = prime * result + ((!backlog) ? 0 : backlog.hashCode())
        result = prime * result + ((!parentSprint) ? 0 : parentSprint.hashCode())
        result = prime * result + ((!state) ? 0 : state.hashCode())
        return result
    }

    @Override
    boolean equals(obj) {
        if (this.is(obj)) {
            return true
        }
        if (obj == null) {
            return false
        }
        if (getClass() != obj.getClass()) {
            return false
        }
        Story other = (Story) obj
        if (effort == null) {
            if (other.effort != null) {
                return false
            }
        } else if (!effort.equals(other.effort)) {
            return false
        }
        if (name == null) {
            if (other.name != null) {
                return false
            }
        } else if (!name.equals(other.name)) {
            return false
        }
        if (backlog == null) {
            if (other.backlog != null) {
                return false
            }
        } else if (!backlog.equals(other.backlog)) {
            return false
        }
        if (parentSprint == null) {
            if (other.parentSprint != null) {
                return false
            }
        } else if (!parentSprint.equals(other.parentSprint)) {
            return false
        }
        if (state == null) {
            if (other.state != null) {
                return false
            }
        } else if (!state.equals(other.state)) {
            return false
        }
        return true
    }

    static search(project, options, rowCount = false) {
        List<Story> stories = []
        def getList = { it instanceof List ? it : (it instanceof Object[] ? it as List : [it]) }
        def criteria = {
            if (rowCount) {
                projections {
                    count()
                }
            }
            backlog {
                eq 'id', project
            }
            if (options.story) {
                if (options.story.term) {
                    or {
                        if (options.story.term instanceof List) {
                            options.story.term.each {
                                ilike 'name', '%' + it + '%'
                                ilike 'description', '%' + it + '%'
                                ilike 'notes', '%' + it + '%'
                            }
                        } else if (options.story.term?.isInteger()) {
                            eq 'uid', options.story.term.toInteger()
                        } else {
                            ilike 'name', '%' + options.story.term + '%'
                            ilike 'description', '%' + options.story.term + '%'
                            ilike 'notes', '%' + options.story.term + '%'
                        }
                    }
                }
                if (options.story.origin) {
                    or {
                        getList(options.story.origin).each { origin ->
                            ilike 'origin', '%' + origin + '%'
                        }
                    }
                }
                if (options.story.feature) {
                    feature {
                        or {
                            getList(options.story.feature).each { feature ->
                                eq 'id', new Long(feature)
                            }
                        }
                    }
                }
                if (options.story.actor) {
                    actors {
                        or {
                            getList(options.story.actor).each { actor ->
                                eq 'id', new Long(actor)
                            }
                        }
                    }
                }
                if (options.story.state) {
                    or {
                        getList(options.story.state).each { state ->
                            eq 'state', new Integer(state)
                        }
                    }
                }
                if (options.story.parentRelease) {
                    parentSprint {
                        parentRelease {
                            or {
                                getList(options.story.parentRelease).each { parentRelease ->
                                    eq 'id', new Long(parentRelease)
                                }
                            }
                        }
                    }
                }
                if (options.story.parentSprint) {
                    parentSprint {
                        or {
                            getList(options.story.parentSprint).each { parentSprint ->
                                eq 'id', new Long(parentSprint)
                            }
                        }
                    }
                }
                if (options.story.creator) {
                    creator {
                        or {
                            getList(options.story.creator).each { creator ->
                                eq 'id', new Long(creator)
                            }
                        }
                    }
                }
                if (options.story.type != null) { // Be careful type user story is 0 so it is falsy
                    or {
                        getList(options.story.type).each { type ->
                            eq 'type', new Integer(type)
                        }
                    }
                }
                if (options.story.dependsOn) {
                    dependsOn {
                        or {
                            getList(options.story.dependsOn).each { dependsOn ->
                                eq 'id', new Long(dependsOn)
                            }
                        }
                    }
                }
                if (options.story.effort) {
                    or {
                        getList(options.story.effort).each { effort ->
                            eq 'effort', new BigDecimal(effort)
                        }
                    }
                }
                if (options.story.affectedVersion) {
                    or {
                        getList(options.story.affectedVersion).each { affectedVersion ->
                            eq 'affectVersion', affectedVersion
                        }
                    }
                }
                if (options.story.deliveredVersion) {
                    parentSprint {
                        or {
                            getList(options.story.deliveredVersion).each { deliveredVersion ->
                                eq 'deliveredVersion', deliveredVersion
                            }
                        }
                    }
                }
            }
        }
        def criteriaCall = {
            criteria.delegate = delegate
            criteria.call()
        }
        if (options.story?.tag) {
            stories = Story.findAllByTagsWithCriteria(getList(options.story.tag), criteriaCall)
        } else if (options.story != null) {
            stories = Story.createCriteria().list(options.list ?: [:], criteriaCall)
        }
        if (rowCount) {
            return stories ? stories.get(0) : 0
        } else {
            return stories ?: Collections.EMPTY_LIST
        }
    }

    enum TestState {

        NOTEST(0),
        TOCHECK(1),
        FAILED(5),
        SUCCESS(10)

        final Integer id

        static TestState byId(Integer id) { values().find { TestState stateEnum -> stateEnum.id == id } }

        private TestState(Integer id) { this.id = id }

        String toString() { "is.story.teststate." + name().toLowerCase() }
    }

    TestState getTestStateEnum() {
        Map testsByStateCount = countTestsByState()
        if (testsByStateCount.size() == 0) {
            TestState.NOTEST
        } else if (testsByStateCount[AcceptanceTestState.FAILED] > 0) {
            TestState.FAILED
        } else if (testsByStateCount[AcceptanceTestState.TOCHECK] > 0) {
            TestState.TOCHECK
        } else {
            TestState.SUCCESS
        }
    }

    Map countTestsByState() {
        // Criteria didn't work because sort on acceptanceTests uid isn't in "group by" clause
        Story.executeQuery("""
            SELECT test.state, COUNT(test.id)
            FROM Story story INNER JOIN story.acceptanceTests AS test
            WHERE story.id = :id
            GROUP BY test.state
            ORDER BY test.state ASC """, [id: id], [cache: true, readOnly: true]
        ).inject([:]) { countByState, group ->
            def (state, stateCount) = group
            if (AcceptanceTestState.exists(state)) {
                countByState[AcceptanceTestState.byId(state)] = stateCount
            }
            countByState
        }
    }

    def xml(builder) {
        builder.story(uid: this.uid) {
            builder.type(this.type)
            builder.rank(this.rank)
            builder.state(this.state)
            builder.value(this.value)
            builder.effort(this.effort)
            builder.doneDate(this.doneDate)
            builder.plannedDate(this.plannedDate)
            builder.acceptedDate(this.acceptedDate)
            builder.todoDate(this.todoDate)
            builder.lastUpdated(this.lastUpdated)
            builder.dateCreated(this.dateCreated)
            builder.affectVersion(this.affectVersion)
            builder.frozenDate(this.frozenDate)
            builder.suggestedDate(this.suggestedDate)
            builder.estimatedDate(this.estimatedDate)
            builder.inProgressDate(this.inProgressDate)
            builder.inReviewDate(this.inReviewDate)

            builder.tags { builder.mkp.yieldUnescaped("<![CDATA[${this.tags ?: ''}]]>") }
            builder.name { builder.mkp.yieldUnescaped("<![CDATA[${this.name}]]>") }
            builder.notes { builder.mkp.yieldUnescaped("<![CDATA[${this.notes ?: ''}]]>") }
            builder.origin { builder.mkp.yieldUnescaped("<![CDATA[${this.origin ?: ''}]]>") }
            builder.description { builder.mkp.yieldUnescaped("<![CDATA[${this.description ?: ''}]]>") }

            builder.creator(uid: this.creator.uid)
            if (this.feature) {
                builder.feature(uid: this.feature.uid)
            }
            if (dependsOn && dependsOn.backlog.id == this.backlog.id) {
                builder.dependsOn(uid: this.dependsOn.uid)
            }

            builder.comments() {
                this.comments.each { _comment ->
                    builder.comment() {
                        builder.dateCreated(_comment.dateCreated)
                        builder.posterId(_comment.posterId)
                        builder.posterClass(_comment.posterClass)
                        builder.body { builder.mkp.yieldUnescaped("<![CDATA[${_comment.body}]]>") }
                    }
                }
            }
            builder.activities() {
                this.activities.each { _activity ->
                    _activity.xml(builder)
                }
            }
            builder.acceptanceTests() {
                this.acceptanceTests.each { _acceptanceTest ->
                    _acceptanceTest.xml(builder)
                }
            }
            builder.actors() {
                this.actors.each { _actor ->
                    builder.actor(uid: _actor.uid)
                }
            }
            builder.followers() {
                this.followers.each { _follower ->
                    builder.user(uid: _follower.uid)
                }
            }
            builder.tasks() {
                this.tasks.sort { a, b ->
                    a.state <=> b.state ?: a.rank <=> b.rank
                }.each { _task ->
                    _task.xml(builder)
                }
            }
            builder.attachments() {
                this.attachments.each { _att ->
                    _att.xml(builder)
                }
            }
            exportDomainsPlugins(builder)
        }
    }
}
