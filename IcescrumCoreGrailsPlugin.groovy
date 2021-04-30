/*
* Copyright (c) 2011 Kagilum SAS
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


import com.quirklabs.hdimageutils.HdImageService
import grails.converters.JSON
import grails.plugin.springsecurity.SecurityFilterPosition
import grails.plugin.springsecurity.SpringSecurityService
import grails.plugin.springsecurity.SpringSecurityUtils
import org.codehaus.groovy.grails.commons.ControllerArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsClassUtils
import org.codehaus.groovy.grails.commons.ServiceArtefactHandler
import org.codehaus.groovy.grails.orm.support.GroovyAwareNamedTransactionAttributeSource
import org.codehaus.groovy.grails.plugins.jasper.JasperExportFormat
import org.codehaus.groovy.grails.plugins.jasper.JasperReportDef
import org.codehaus.groovy.grails.plugins.jasper.JasperService
import org.icescrum.atmosphere.AtmosphereUser
import org.icescrum.core.app.AppDefinitionArtefactHandler
import org.icescrum.core.cache.IsControllerWebKeyGenerator
import org.icescrum.core.cors.CorsFilter
import org.icescrum.core.event.IceScrumEventPublisher
import org.icescrum.core.event.IceScrumEventType
import org.icescrum.core.event.IceScrumListener
import org.icescrum.core.security.IceScrumRedirectStrategy
import org.icescrum.core.security.IceScrumSimpleUrlLogoutSuccessHandler
import org.icescrum.core.security.ScrumUserDetailsService
import org.icescrum.core.security.rest.TokenAuthenticationFilter
import org.icescrum.core.security.rest.TokenAuthenticationProvider
import org.icescrum.core.security.rest.TokenStorageService
import org.icescrum.core.services.AppDefinitionService
import org.icescrum.core.services.UiDefinitionService
import org.icescrum.core.support.ApplicationSupport
import org.icescrum.core.support.ProgressSupport
import org.icescrum.core.ui.UiDefinitionArtefactHandler
import org.icescrum.core.utils.JSONIceScrumDomainClassMarshaller
import org.icescrum.core.utils.RollbackAlwaysTransactionAttribute
import org.icescrum.plugins.attachmentable.domain.Attachment
import org.icescrum.plugins.attachmentable.services.AttachmentableService
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.access.ExceptionTranslationFilter
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.web.context.request.RequestContextHolder as RCH
import org.springframework.web.servlet.support.RequestContextUtils as RCU

import javax.servlet.http.HttpServletResponse
import java.lang.reflect.Method

class IcescrumCoreGrailsPlugin {
    def groupId = 'org.icescrum'
    def version = "1.7-SNAPSHOT"
    def grailsVersion = "2.5 > *"
    def artefacts = [UiDefinitionArtefactHandler, AppDefinitionArtefactHandler]
    def watchedResources = [
            "file:./grails-app/icescrum/*UiDefinition.groovy",
            "file:../plugins/*/grails-app/icescrum/*UiDefinition.groovy",
            "file:./grails-app/icescrum/*Apps.groovy",
            "file:../plugins/*/grails-app/icescrum/*Apps.groovy",
            "file:./grails-app/services/*Service.groovy"
    ]
    def observe = ['controllers', 'services']
    def loadAfter = ['controllers', 'feeds', 'hibernate', 'springSecurityCore', 'cache']
    def loadBefore = ['grails-atmosphere-meteor', 'asset-pipeline']
    def author = "iceScrum"
    def authorEmail = "contact@icescrum.org"
    def title = "iceScrum core plugin (include domain / services / taglib)"
    def description = '''iceScrum core plugin (include domain / services / taglib)'''
    def documentation = "https://www.icescrum.com/documentation"

    def doWithWebDescriptor = { xml ->
        if (application.config.icescrum.push.enable) {
            addAtmosphereSessionSupport(xml)
        }
        def cors = application.config.icescrum.cors
        if (cors.enable) {
            addCorsSupport(xml, cors)
        }
    }

    def controllersWithDownloadAndPreview = ['story', 'task', 'feature', 'sprint', 'release', 'project']

    def doWithSpring = {
        println 'Configuring iceScrum...'
        // If grails.serverURL, it's likely to be an iceScrum R6 config so we stop the startup
        if (application.config.grails.serverURL) {
            println """
------------------------------------------------------------
ERROR: iceScrum v7 has detected that you attempt to run it on an existing R6 installation. This will not work!
 - If you want to start new projects or evaluate this new version, the best solution consists in installing it on a new server.
 - If this server is not a production server then you can clean everything related to your R6 installation (database, ~/.icescrum, ~/icescrum directories, Tomcat dirs...) and try again.
 - If you want to migrate an existing R6 production server to v7 then follow this documentation: https://www.icescrum.com/documentation/migration-standalone/.
------------------------------------------------------------
 """
            throw new RuntimeException('\nERROR: Cannot run iceScrum v7 on an iceScrum R6 installation')
        }
        ApplicationSupport.initEnvironment(application.config) // DO NOT MOVE IT ELSEWHERE, IT MUST BE DONE BEFORE CREATING UUID
        ApplicationSupport.createUUID()
        System.setProperty('lbdsl.home', "${application.config.icescrum.baseDir.toString()}${File.separator}lbdsl")
        // Init config.icescrum.export for plugins to be able to register without an if exist / create test
        application.config?.icescrum?.export = [:]
        application.domainClasses.each {
            if (it.metaClass.methods*.name.any { it == 'xml' }) {
                application.config?.icescrum?.export."${it.propertyName}" = []
            }
        }
        application.serviceClasses.each {
            if (it.metaClass.methods*.name.any { it == 'unMarshall' }) {
                application.config?.icescrum?.import."${it.logicalPropertyName}" = []
            }
        }

        for (String beanName : getSpringConfig().getBeanNames()) {
            def definition = getSpringConfig().getBeanConfig(beanName)?.getBeanDefinition()
            if (definition) {
                makeServiceMethodsRollbackOnAnyThrowable definition
            }
        }

        userDetailsService(ScrumUserDetailsService) {
            grailsApplication = ref('grailsApplication')
        }

        tokenAuthenticationProvider(TokenAuthenticationProvider) {
            tokenStorageService = ref('tokenStorageService')
        }

        tokenAuthenticationFilter(TokenAuthenticationFilter) {
            authenticationManager = ref('authenticationManager')
        }

        tokenStorageService(TokenStorageService) {
            userDetailsService = ref('userDetailsService')
        }

        restAccessDeniedHandler(AccessDeniedHandlerImpl) {
            errorPage = null // 403
        }

        restExceptionTranslationFilter(ExceptionTranslationFilter, ref('restAuthenticationEntryPoint'), ref('restRequestCache')) {
            accessDeniedHandler = ref('restAccessDeniedHandler')
            authenticationTrustResolver = ref('authenticationTrustResolver')
            throwableAnalyzer = ref('throwableAnalyzer')
        }

        restAuthenticationEntryPoint(Http403ForbiddenEntryPoint)
        restRequestCache(NullRequestCache)

        SpringSecurityUtils.registerProvider 'tokenAuthenticationProvider'
        SpringSecurityUtils.registerFilter 'tokenAuthenticationFilter', SecurityFilterPosition.BASIC_AUTH_FILTER.order - 1
        SpringSecurityUtils.registerFilter 'restExceptionTranslationFilter', SecurityFilterPosition.EXCEPTION_TRANSLATION_FILTER.order + 1

        redirectStrategy(IceScrumRedirectStrategy) {
            useHeaderCheckChannelSecurity = SpringSecurityUtils.securityConfig.secureChannel.useHeaderCheckChannelSecurity // false
            portResolver = ref('portResolver')
        }

        logoutSuccessHandler(IceScrumSimpleUrlLogoutSuccessHandler) {
            redirectStrategy = ref('redirectStrategy')
            defaultTargetUrl = SpringSecurityUtils.securityConfig.logout.afterLogoutUrl // '/'
            alwaysUseDefaultTargetUrl = SpringSecurityUtils.securityConfig.logout.alwaysUseDefaultTargetUrl // false
            targetUrlParameter = SpringSecurityUtils.securityConfig.logout.targetUrlParameter // null
            useReferer = SpringSecurityUtils.securityConfig.logout.redirectToReferer // false
        }

        webCacheKeyGenerator(IsControllerWebKeyGenerator)
    }

    def doWithDynamicMethods = { ctx ->
        // Manually match the UIController classes
        SpringSecurityService springSecurityService = ctx.getBean('springSecurityService')
        HdImageService hdImageService = ctx.getBean('hdImageService')
        AttachmentableService attachmentableService = ctx.getBean('attachmentableService')
        JasperService jasperService = ctx.getBean('jasperService')
        UiDefinitionService uiDefinitionService = ctx.getBean('uiDefinitionService')
        AppDefinitionService appDefinitionService = ctx.getBean('appDefinitionService')
        uiDefinitionService.loadDefinitions()
        appDefinitionService.loadAppDefinitions()
        application.controllerClasses.each {
            addCleanBeforeBindData(it)
            addJasperMethod(it, springSecurityService, jasperService)
            if (it.logicalPropertyName in controllersWithDownloadAndPreview) {
                addDownloadAndPreviewMethods(it, attachmentableService, hdImageService)
            }
        }
        application.serviceClasses.each {
            addListenerSupport(it, ctx)
        }
        application.domainClasses.each {
            addExportDomainsPlugins(it, application.config.icescrum.export)
        }
        application.serviceClasses.each {
            addImportDomainsPlugins(it, application.config.icescrum.import)
        }
    }

    def doWithApplicationContext = { applicationContext ->
        Map properties = application.config?.icescrum?.marshaller
        JSON.registerObjectMarshaller(new JSONIceScrumDomainClassMarshaller(application, properties), 1)
        JSON.registerObjectMarshaller(AtmosphereUser) {
            def marshalledUser = [:]
            marshalledUser['id'] = it.id
            marshalledUser['username'] = it.username
            marshalledUser['connections'] = it.connections?.collect {
                [
                        'window'   : it.window,
                        'ipAddress': it.ipAddress,
                        'uuid'     : it.uuid,
                        'transport': it.transport
                ]
            } ?: []
            return marshalledUser
        }
        applicationContext.bootStrapService.start()
    }

    def onChange = { event ->
        UiDefinitionService uiDefinitionService = event.ctx.getBean('uiDefinitionService')
        def reloadArtefact = { type ->
            def oldClass = application.getArtefact(type, event.source.name)
            application.addArtefact(type, event.source)
            application.getArtefacts(type).each {
                if (it.clazz != event.source && oldClass.clazz.isAssignableFrom(it.clazz)) {
                    def newClass = application.classLoader.reloadClass(it.clazz.name)
                    application.addArtefact(type, newClass)
                }
            }
        }
        def uiDefinitionType = UiDefinitionArtefactHandler.TYPE
        def appsType = AppDefinitionArtefactHandler.TYPE
        if (application.isArtefactOfType(uiDefinitionType, event.source)) {
            reloadArtefact(uiDefinitionType)
            uiDefinitionService.reload()
        } else if (application.isArtefactOfType(appsType, event.source)) {
            reloadArtefact(appsType)
            ((AppDefinitionService) event.ctx.getBean('appDefinitionService')).reloadAppDefinitions()
        } else if (application.isArtefactOfType(ControllerArtefactHandler.TYPE, event.source)) {
            def controller = application.getControllerClass(event.source?.name)
            HdImageService hdImageService = event.ctx.getBean('hdImageService')
            AttachmentableService attachmentableService = event.ctx.getBean('attachmentableService')
            if (uiDefinitionService.hasWindowDefinition(controller.logicalPropertyName)) {
                if (controller.logicalPropertyName in controllersWithDownloadAndPreview) {
                    addDownloadAndPreviewMethods(controller, attachmentableService, hdImageService)
                }
            }
            if (application.isControllerClass(event.source)) {
                SpringSecurityService springSecurityService = event.ctx.getBean('springSecurityService')
                JasperService jasperService = event.ctx.getBean('jasperService')
                addCleanBeforeBindData(event.source)
                addJasperMethod(event.source, springSecurityService, jasperService)
            }
        }
        if (application.isArtefactOfType(ServiceArtefactHandler.TYPE, event.source)) {
            def serviceClass = application.getArtefact(ServiceArtefactHandler.TYPE, event.source.name)
            def definition = event.ctx.getBeanDefinition(serviceClass.propertyName)
            makeServiceMethodsRollbackOnAnyThrowable definition
        }
    }

    def onConfigChange = { event ->
        event.application.mainContext.uiDefinitionService.reload()
        ((AppDefinitionService) event.application.mainContext.appDefinitionService).reloadAppDefinitions()
    }

    private addDownloadAndPreviewMethods(clazz, attachmentableService, hdImageService) {
        def mc = clazz.metaClass
        def dynamicActions = [
                download: { ->
                    Attachment attachment = Attachment.get(params.id as Long)
                    if (attachment) {
                        if (attachment.url) {
                            redirect(url: "${attachment.url}")
                            return
                        } else {
                            File file = attachmentableService.getFile(attachment)

                            if (file.exists()) {
                                if (!attachment.previewable) {
                                    String filename = attachment.filename
                                    ['Content-disposition': "attachment;filename=\"$filename\"", 'Cache-Control': 'private', 'Pragma': ''].each { k, v ->
                                        response.setHeader(k, v)
                                    }
                                }
                                response.contentType = attachment.contentType
                                response.outputStream << file.newInputStream()
                                return
                            }
                        }
                    }
                    response.status = HttpServletResponse.SC_NOT_FOUND
                },
                preview : {
                    Attachment attachment = Attachment.get(params.id as Long)
                    File file = attachmentableService.getFile(attachment)
                    def thumbnail = new File(file.parentFile.absolutePath + File.separator + attachment.id + '-thumbnail.' + (attachment.ext?.toLowerCase() != 'gif' ? attachment.ext : 'jpg'))
                    if (!thumbnail.exists()) {
                        thumbnail.setBytes(hdImageService.scale(file.absolutePath, 40, 40))
                    }
                    if (thumbnail.exists()) {
                        response.contentType = attachment.contentType
                        response.outputStream << thumbnail.newInputStream()
                    } else {
                        render(status: 404)
                    }
                }
        ]
        dynamicActions.each { actionName, actionClosure ->
            mc."${GrailsClassUtils.getGetterName(actionName)}" = { ->
                actionClosure.delegate = delegate
                actionClosure.resolveStrategy = Closure.DELEGATE_FIRST
                actionClosure
            }
            clazz.registerMapping(actionName)
        }
    }

    private void addExportDomainsPlugins(source, config) {
        source.metaClass.exportDomainsPlugins = { builder ->
            def domainObject = delegate
            def progress = RCH.currentRequestAttributes().getSession()?.progress
            if (progress) {
                if (!progress.buffer?.contains(source.propertyName)) {
                    if (!progress.buffer) {
                        progress.buffer = []
                    }
                    progress.buffer << source.propertyName
                    def newValue = (progress.buffer.size() * 90) / (config.size() * progress.multiple)
                    progress.updateProgress(newValue, source.propertyName)
                }
            }
            config[source.propertyName]?.each { closure ->
                closure.delegate = domainObject
                closure(domainObject, builder)
            }
        }
    }

    private void addImportDomainsPlugins(source, config) {
        def name = source.logicalPropertyName
        source.metaClass.importDomainsPlugins = { objectXml, object, options ->
            def progress = RCH.currentRequestAttributes().getSession()?.progress
            if (progress) {
                if (!progress.buffer?.contains(name)) {
                    if (!progress.buffer) {
                        progress.buffer = []
                    }
                    progress.buffer << name
                    def newValue = (progress.buffer.size() * 90) / (config.size() * progress.multiple)
                    progress.updateProgress(newValue, name)
                }
            }
            config[name]?.each { closure ->
                closure(objectXml, object, options)
            }
            return object
        }
    }

    private void addCleanBeforeBindData(source) {
        source.metaClass.cleanBeforeBindData = { def params, def elems ->
            def toRemove = params.keySet().findAll { String key ->
                return elems.find { prefix -> key != prefix + '.id' && key.startsWith(prefix + '.') }
            }
            def removeProperty // Recursive so must be declared beforehand to be in scope
            removeProperty = { obj, fullKey ->
                String[] key = fullKey.split(/\./, 2)
                if (key.size() == 2) {
                    obj."${key[0]}"?.remove(key[1])
                    if (key[1]?.contains('.') && obj."${key[0]}") {
                        removeProperty(obj."${key[0]}", key[1])
                    }
                    obj."${key[0]}"?.remove(key[1].split(/\./, 2)[0])
                }
                obj.remove(fullKey)
            }
            toRemove.each { String fullKey ->
                removeProperty(params, fullKey)
            }
            return params
        }
    }

    private void addJasperMethod(source, springSecurityService, jasperService) {
        try {
            //Only for DEV, Compile reports when changing jrxml for subreports without install ireport designer...
            /*source.metaClass.compileReport = {
                // Get currently running directory
                String currentPath = System.getProperty("user.dir");
                System.out.println("Current path is: " + currentPath);

                // Go to directory where all the reports are
                File rootDir = new File(currentPath + "/web-app/reports");

                // Get all *.jrxml files
                Collection<File> files = FileUtils.listFiles(rootDir,
                        new RegexFileFilter("^(.*\\.jrxml)"), TrueFileFilter.INSTANCE);

                for (File file : files) {
                    System.out.println("Compiling: " + file.getAbsolutePath());
                    System.out.println("Output: " + file.getName() + ".jasper");
                    // Actual compiling
                    JasperCompileManager.compileReportToFile(file.getAbsolutePath(), currentPath + "/web-app/reports/subreports2/" + FilenameUtils.getBaseName(file.getName()) + ".jasper");
                    System.out.println("Compiling: completed!");
                }
            }*/
            source.metaClass.renderReport = { String reportName, String format, def data, String outputName = null, def parameters = null ->
                outputName = (outputName ? outputName.replaceAll("[^\\-a-zA-Z\\s]", "").replaceAll(" ", "") + '-' + reportName : reportName) + '-' + (g.formatDate(formatName: 'is.date.file'))
                if (!session.progress) {
                    session.progress = new ProgressSupport()
                }
                session.progress.updateProgress(50, message(code: 'is.report.processing'))
                if (parameters) {
                    parameters.SUBREPORT_DIR = "${servletContext.getRealPath('/reports/subreports')}/"
                } else {
                    parameters = [SUBREPORT_DIR: "${servletContext.getRealPath('/reports/subreports')}/"]
                }

                def reportDef = new JasperReportDef(name: reportName,
                        reportData: data,
                        locale: springSecurityService.isLoggedIn() ? springSecurityService.currentUser.locale : RCU.getLocale(request),
                        parameters: parameters,
                        fileFormat: JasperExportFormat.determineFileFormat(format))

                response.characterEncoding = "UTF-8"
                response.setHeader("Content-disposition", "attachment; filename=" + outputName + "." + reportDef.fileFormat.extension)
                session.progress?.completeProgress(message(code: 'is.report.complete'))
                render(file: jasperService.generateReport(reportDef).toByteArray(), contentType: reportDef.fileFormat.mimeTyp)
            }
        } catch (Exception e) {
            if (log.debugEnabled) e.printStackTrace()
            session.progress.progressError(message(code: 'is.report.error'))
        }
    }

    // Websockets are not backed by HttpSessions, which prevents from looking at the security context
    // One way to work around that consists in enabling HttpSession support at the atmosphere level
    // References:
    // - https://github.com/Atmosphere/atmosphere/wiki/Enabling-HttpSession-Support
    // - https://spring.io/blog/2014/09/16/preview-spring-security-websocket-support-sessions
    private addAtmosphereSessionSupport(xml) {
        def nodeName = 'listener' // An 1 level indirection is required, using the "listener" string literal fails miserably
        def listener = xml[nodeName]
        listener[listener.size() - 1] + {
            "$nodeName" {
                'listener-class'('org.atmosphere.cpr.SessionSupport')
            }
        }
        def contextParam = xml.'context-param'
        contextParam[contextParam.size() - 1] + {
            'context-param' {
                'param-name'('org.atmosphere.cpr.sessionSupport')
                'param-value'(true)
            }
        }
    }

    private addCorsSupport(def xml, def config) {
        def contextParam = xml.'context-param'
        contextParam[contextParam.size() - 1] + {
            'filter' {
                'filter-name'('cors-headers')
                'filter-class'(CorsFilter.name)
                if (config.allow.origin.regex) {
                    'init-param' {
                        'param-name'('allow.origin.regex')
                        'param-value'(config.allow.origin.regex.toString())
                    }
                }
                if (config.allowedHeaders && config.allowedHeaders instanceof List) {
                    'init-param' {
                        'param-name'('allowedHeaders')
                        'param-value'(config.allowedHeaders.join(', '))
                    }
                }
            }
        }
        def urlPattern = config.urlPatterns ?: '/*'
        List list = urlPattern instanceof List ? urlPattern : [urlPattern]
        def filter = xml.'filter'
        list.each { pattern ->
            filter[0] + {
                'filter-mapping' {
                    'filter-name'('cors-headers')
                    'url-pattern'(pattern)
                }
            }
        }
    }

    private void addListenerSupport(serviceGrailsClass, ctx) {
        serviceGrailsClass.clazz.declaredMethods.each { Method method ->
            IceScrumListener listener = method.getAnnotation(IceScrumListener)
            if (listener) {
                def domains = listener.domain() ? [listener.domain()] : listener.domains()
                domains.each { domain ->
                    def publisherService = domain != '*' ? ctx.getBean(domain + 'Service') : ctx.getBean('projectService')
                    if (publisherService && publisherService instanceof IceScrumEventPublisher) {
                        def serviceName = serviceGrailsClass.propertyName
                        if (listener.eventType() == IceScrumEventType.UGLY_HACK_BECAUSE_ANNOTATION_CANT_BE_NULL) {
//                            println 'Add listener on all ' + domain + ' events: ' + serviceGrailsClass.propertyName + '.' + method.name
                            publisherService.registerListener(domain) { eventType, object, dirtyProperties ->
                                ctx.getBean(serviceName)."$method.name"(eventType, object, dirtyProperties) // Service bean must be loaded in the callback, not extracted above, because we need the freshest one
                            }
                        } else {
//                            println 'Add listener on ' + domain + ' ' + listener.eventType().toString() + ' events: ' + serviceGrailsClass.propertyName + '.' + method.name
                            publisherService.registerListener(domain, listener.eventType()) { eventType, object, dirtyProperties ->
                                ctx.getBean(serviceName)."$method.name"(object, dirtyProperties)  // Service bean must be loaded in the callback, not extracted above, because we need the freshest one
                            }
                        }
                    }
                }
            }
        }
    }

    private void makeServiceMethodsRollbackOnAnyThrowable(BeanDefinition definition) {
        if (definition.beanClassName == 'org.codehaus.groovy.grails.commons.spring.TypeSpecifyableTransactionProxyFactoryBean') {
            def methodMatchingMap = ['*': new RollbackAlwaysTransactionAttribute()]
            def source = new GroovyAwareNamedTransactionAttributeSource(nameMap: methodMatchingMap)
            definition.propertyValues.addPropertyValue('transactionAttributeSource', source)
        }
    }
}
