/*
 * Copyright (c) 2015 Kagilum SAS
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
 * Manuarii Stein (manuarii.stein@icescrum.com)
 * Nicolas Noullet (nnoullet@kagilum.com)
 */


package org.icescrum.core.domain

import grails.util.Holders
import org.grails.comments.Comment
import org.hibernate.ObjectNotFoundException

class Feature extends BacklogElement implements Serializable {
    static final long serialVersionUID = 7072515028109185168L

    static final int TYPE_FUNCTIONAL = 0
    static final int TYPE_ENABLER = 1
    static final int STATE_DRAFT = -1
    static final int STATE_WAIT = 0
    static final int STATE_BUSY = 1
    static final int STATE_DONE = 2
    static final String DEFAULT_COLOR = '#0067e8'

    String color = DEFAULT_COLOR

    Integer value = null
    Date doneDate
    int type = Feature.TYPE_FUNCTIONAL
    int rank

    static transients = ['countDoneStories', 'state', 'effort', 'inProgressDate', 'project', 'actualReleases']

    static belongsTo = [
            portfolio    : Portfolio,
            parentRelease: Release
    ]

    static hasMany = [stories: Story]

    static mappedBy = [stories: "feature"]

    static mapping = {
        cache true
        table 'is_feature'
        stories cascade: "refresh", sort: 'rank', 'name': 'asc', batchSize: 25, cache: true
        sort "id"
        metaDatas cascade: 'delete-orphan', batchSize: 10, cache: true // Doesn't work on BacklogElement
        activities cascade: 'delete-orphan', batchSize: 25, cache: true // Doesn't work on BacklogElement
    }

    static constraints = {
        name(unique: 'backlog')
        backlog nullable: true, validator: { newBacklog, feature -> (!newBacklog && feature.portfolio || newBacklog && !feature.portfolio) ?: 'invalid' }
        portfolio nullable: true
        parentRelease(nullable: true)
        value(nullable: true)
        doneDate(nullable: true)
    }

    static namedQueries = {
        getInProject { p, id ->
            backlog {
                eq 'id', p
            }
            and {
                eq 'id', id
            }
            uniqueResult = true
        }
        getInPortfolio { p, id ->
            portfolio {
                eq 'id', p
            }
            and {
                eq 'id', id
            }
            uniqueResult = true
        }
    }

    static List<Comment> recentCommentsInProject(long projectId) {
        return executeQuery(""" 
                SELECT commentLink.comment 
                FROM Feature feature, CommentLink as commentLink 
                WHERE feature.backlog.id = :projectId 
                AND commentLink.commentRef = feature.id 
                AND commentLink.type = 'feature'
                ORDER BY commentLink.comment.dateCreated DESC""", [projectId: projectId], [max: 10, offset: 0, cache: true, readOnly: true]
        )
    }

    static List<Comment> recentCommentsInPortfolio(long portfolioId) {
        return executeQuery(""" 
                SELECT commentLink.comment 
                FROM Feature feature, CommentLink as commentLink 
                WHERE feature.portfolio.id = :portfolioId 
                AND commentLink.commentRef = feature.id 
                AND commentLink.type = 'feature'
                ORDER BY commentLink.comment.dateCreated DESC""", [portfolioId: portfolioId], [max: 10, offset: 0, cache: true, readOnly: true]
        )
    }

    static Feature withFeature(long workspaceId, long id, String workspaceType = WorkspaceType.PROJECT) {
        Feature feature
        if (workspaceType == WorkspaceType.PROJECT) {
            feature = (Feature) getInProject(workspaceId, id).list()
        } else if (workspaceType == WorkspaceType.PORTFOLIO) {
            feature = (Feature) getInPortfolio(workspaceId, id).list()
        }
        if (!feature) {
            throw new ObjectNotFoundException(id, 'Feature')
        }
        return feature
    }

    static List<Feature> withFeatures(def params, def id = 'id', String workspaceType = WorkspaceType.PROJECT) {
        def ids = params[id]?.contains(',') ? params[id].split(',')*.toLong() : params.list(id)
        List<Feature> features = ids ? getAll(ids).findAll { Feature feature ->
            feature && (workspaceType == WorkspaceType.PROJECT && feature.backlog.id == params.project.toLong() || workspaceType == WorkspaceType.PORTFOLIO && feature.portfolio.id == params.portfolio.toLong())
        } : null
        if (!features) {
            throw new ObjectNotFoundException(ids, 'Feature')
        }
        return features
    }

    int hashCode() {
        final int prime = 31
        int result = 1
        result = prime * result + ((!name) ? 0 : name.hashCode())
        result = prime * result + ((!backlog) ? 0 : backlog.hashCode())
        return result
    }

    boolean equals(Object obj) {
        if (this.is(obj)) {
            return true
        }
        if (obj == null) {
            return false
        }
        if (getClass() != obj.getClass()) {
            return false
        }
        final Feature other = (Feature) obj
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
        return true
    }

    static Integer findNextUId(Long workspaceId, String workspaceType) {
        if (workspaceType == WorkspaceType.PROJECT) {
            return (executeQuery("""
                   SELECT MAX(feature.uid)
                   FROM Feature feature, Project project
                   WHERE feature.backlog = project
                   AND project.id = :projectId """, [projectId: workspaceId])[0] ?: 0) + 1
        } else if (workspaceType == WorkspaceType.PORTFOLIO) {
            return (executeQuery("""
                   SELECT MAX(feature.uid)
                   FROM Feature feature, Portfolio portfolio
                   WHERE feature.portfolio = portfolio
                   AND portfolio.id = :portfolioId """, [portfolioId: workspaceId])[0] ?: 0) + 1
        }
    }

    def getCountDoneStories() {
        return stories?.sum { (it.state == Story.STATE_DONE) ? 1 : 0 } ?: 0
    }

    def getState() {
        if (doneDate) {
            return STATE_DONE
        } else if (stories && stories.find { it.state > Story.STATE_PLANNED }) {
            return STATE_BUSY
        } else if (portfolio) {
            return STATE_DRAFT
        } else {
            return STATE_WAIT
        }
    }

    def getEffort() {
        return stories?.sum { it.effort ?: 0 } ?: 0
    }

    Date getInProgressDate() {
        if (state > STATE_WAIT) {
            def inProgressDates = stories.collect { it.inProgressDate }.findAll { it != null }.sort()
            if (inProgressDates) {
                return inProgressDates.last()
            } else {
                log.error("Error when fetching last in progress date on in progress feature $id: " + stories.collect { story -> [id: story.id, class: story.class.name, state: story.state, inProgressDate: story.inProgressDate] }.inspect())
            }
        } else {
            return null
        }
    }

    def getActivity() {
        def activities = stories*.activities.flatten().findAll { Activity a -> a.important && a.code != Activity.CODE_SAVE }
        return activities.sort { Activity a, Activity b -> b.dateCreated <=> a.dateCreated }
    }

    List<Map> getActualReleases() {
        executeQuery(""" 
                SELECT release.id, release.name, release.state, release.orderNumber
                FROM Release release
                WHERE release.id IN (
                    SELECT DISTINCT release2.id
                    FROM Release release2, Story story, Sprint sprint
                    WHERE story.feature.id = :featureId
                    AND story.parentSprint.id = sprint.id
                    AND sprint.parentRelease.id = release2.id
                )
                ORDER BY release.orderNumber""", [featureId: id], [cache: true, readOnly: true]
        ).collect { columns ->
            [id: columns[0], name: columns[1], state: columns[2], orderNumber: columns[3]]
        }
    }

    Map getProject() { // Hack because by default it does not return the asShort but a timebox instead
        Project project = (Project) backlog
        return project ? [class: 'Project', id: project.id, pkey: project.pkey, name: project.name] : [:]
    }

    String getPermalink() {
        return backlog ? Holders.grailsApplication.config.icescrum.serverURL + '/p/' + backlog.pkey + '-F' + this.uid : ''
    }

    static search(project, options) {
        def criteria = {
            backlog {
                eq 'id', project
            }
            if (options.term || options.feature) {
                if (options.term) {
                    or {
                        if (options.term?.isInteger()) {
                            eq 'uid', options.term.toInteger()
                        } else {
                            ilike 'name', '%' + options.term + '%'
                            ilike 'description', '%' + options.term + '%'
                            ilike 'notes', '%' + options.term + '%'
                        }
                    }
                }
                if (options.feature?.type?.isInteger()) {
                    eq 'type', options.feature.type.toInteger()
                }
            }
        }
        if (options.tag) {
            return Feature.findAllByTagWithCriteria(options.tag) {
                criteria.delegate = delegate
                criteria.call()
            }
        } else if (options.term || options.feature != null) {
            return Feature.createCriteria().list {
                criteria.delegate = delegate
                criteria.call()
            }
        } else {
            return Collections.EMPTY_LIST
        }
    }

    def xml(builder) {
        builder.feature(uid: this.uid) {
            builder.type(this.type)
            builder.rank(this.rank)
            builder.color(this.color)
            builder.value(this.value ?: '')
            builder.todoDate(this.todoDate)
            builder.doneDate(this.doneDate)
            builder.tags { builder.mkp.yieldUnescaped("<![CDATA[${this.tags}]]>") }
            builder.name { builder.mkp.yieldUnescaped("<![CDATA[${this.name}]]>") }
            builder.notes { builder.mkp.yieldUnescaped("<![CDATA[${this.notes ?: ''}]]>") }
            builder.description { builder.mkp.yieldUnescaped("<![CDATA[${this.description ?: ''}]]>") }
            builder.stories() {
                this.stories.sort { it.uid }.each { _story ->
                    story(uid: _story.uid)
                }
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
            builder.attachments() {
                this.attachments.each { _att ->
                    _att.xml(builder)
                }
            }
            exportDomainsPlugins(builder)
        }
    }

}
