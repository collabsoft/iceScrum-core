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
 */


package org.icescrum.core.services

import grails.transaction.Transactional
import org.icescrum.core.domain.Project
import org.icescrum.core.domain.Team
import org.icescrum.core.domain.User
import org.icescrum.core.error.BusinessException
import org.icescrum.core.event.IceScrumEventPublisher
import org.icescrum.core.event.IceScrumEventType
import org.springframework.security.access.prepost.PreAuthorize

@Transactional
class TeamService extends IceScrumEventPublisher {

    def springSecurityService
    def securityService
    def grailsApplication

    void save(Team team, List members, List scrumMasters) {
        if (!team) {
            throw new BusinessException(code: 'is.team.error.not.exist')
        }
        if (!team.save()) {
            throw new BusinessException(code: 'is.team.error.not.saved')
        } else {
            securityService.secureDomain(team)
            if (members) {
                for (member in User.getAll(members)) {
                    if (!scrumMasters?.contains(member.id) && member) {
                        addMember(team, member)
                    }
                }
            }
            if (scrumMasters) {
                for (scrumMaster in User.getAll(scrumMasters)) {
                    if (scrumMaster) {
                        addScrumMaster(team, scrumMaster)
                    }
                }
            }
            team.save(flush: true)
            team.projects = [] // Grails does not initialize the collection and it is serialized as null instead of empty collection
            publishSynchronousEvent(IceScrumEventType.CREATE, team)
        }
    }

    @PreAuthorize('owner(#team)')
    void delete(Team team) {
        if (team.projects) {
            throw new BusinessException(code: 'is.team.error.delete.has.projects')
        }
        def dirtyProperties = publishSynchronousEvent(IceScrumEventType.BEFORE_DELETE, team)
        def teamMembersIds = team.members*.id
        teamMembersIds.each { Long id ->
            removeMemberOrScrumMaster(team, User.get(id))
        }
        team.invitedMembers*.delete()
        team.invitedScrumMasters*.delete()
        team.delete()
        securityService.unsecureDomain(team)
        publishSynchronousEvent(IceScrumEventType.DELETE, team, dirtyProperties)
    }

    @PreAuthorize('isAuthenticated()')
    void saveImport(Team team) {
        //save before go further
        team.members.each { user ->
            user.save()
        }
        team.scrumMasters.each { user ->
            user.save()
        }
        if (!team.save()) {
            throw new BusinessException(code: 'is.team.error.not.saved')
        }
        securityService.secureDomain(team)
        securityService.changeOwner(team.owner, team)
        def scrumMasters = team.scrumMasters
        for (member in team.members) {
            if (!member.isAttached()) {
                member = member.merge()
            }
            if (!(member in scrumMasters)) {
                addMember(team, member)
            }
        }
        if (scrumMasters) {
            scrumMasters.eachWithIndex { it, index ->
                if (!it.isAttached()) {
                    it = it.merge()
                }
                addScrumMaster(team, it)
            }
        } else {
            def user = User.get(springSecurityService.principal.id)
            if (!user.isAttached()) {
                user = user.merge()
            }
            addScrumMaster(team, user)
        }
    }

    void addMember(Team team, User member) {
        if (!team.members*.id?.contains(member.id)) {
            team.addToMembers(member).save()
        }
        securityService.createTeamMemberPermissions(member, team)
    }

    void addScrumMaster(Team team, User member) {
        if (!team.members*.id?.contains(member.id)) {
            team.addToMembers(member).save()
        }
        securityService.createScrumMasterPermissions(member, team)
    }

    void removeMemberOrScrumMaster(Team team, User member) {
        team.removeFromMembers(member).save()
        if (team.scrumMasters*.id?.contains(member.id)) {
            securityService.deleteScrumMasterPermissions(member, team)
        } else {
            securityService.deleteTeamMemberPermissions(member, team)
        }
    }

    def unMarshall(def teamXml, def options) {
        Project project = options.project
        Team.withTransaction(readOnly: !options.save) { transaction ->
            try {
                def existingTeam = true
                def team = new Team(
                        name: teamXml."${'name'}".text(),
                        velocity: (teamXml.velocity.text().isNumber()) ? teamXml.velocity.text().toInteger() : 0,
                        description: teamXml.description.text() ?: null,
                        uid: teamXml.@uid.text() ?: (teamXml."${'name'}".text()).encodeAsMD5()
                )
                team.owner = (!teamXml.owner.user.@uid.isEmpty()) ? ((User) User.findByUid(teamXml.owner.user.@uid.text())) ?: null : null
                def userService = (UserService) grailsApplication.mainContext.getBean('userService')
                teamXml.members.user.eachWithIndex { user, index ->
                    User u = userService.unMarshall(user, options)
                    if (!u.id) {
                        existingTeam = false
                    }
                    if (project) {
                        def uu = (User) team.members.find { it.uid == u.uid } ?: null
                        uu ? team.addToMembers(uu) : team.addToMembers(u)
                    } else {
                        team.addToMembers(u)
                    }
                    if (options.IDUIDUserMatch != null) {
                        options.IDUIDUserMatch."${u.id ?: user.id.text().toInteger()}" = u.uid
                    }
                }
                def scrumMastersList = []
                def sm = teamXml.scrumMasters.user
                sm.eachWithIndex { user, index ->
                    def u = ((User) team.members.find { it.uid == user.@uid.text() }) ?: null
                    scrumMastersList << u
                }
                team.scrumMasters = scrumMastersList
                if (existingTeam) {
                    Team dbTeam = Team.findByName(team.name)
                    if (dbTeam) {
                        if (dbTeam.members.size() != team.members.size()) existingTeam = false
                        if (existingTeam) {
                            for (member in dbTeam.members) {
                                def u = team.members.find { member.uid == it.uid }
                                if (!u) {
                                    existingTeam = false
                                    break
                                }
                            }
                        }
                        team = dbTeam
                    }
                }
                // Reference on other object
                if (project) {
                    project.addToTeams(team)
                }
                if (!existingTeam && options.save) {
                    saveImport(team)
                }
                return (Team) importDomainsPlugins(teamXml, team, options)
            } catch (Exception e) {
                if (log.debugEnabled) e.printStackTrace()
                throw new RuntimeException(e)
            }
        }
    }
}
