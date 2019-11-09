package com.digitalipvoice.cps.components

import org.aopalliance.intercept.MethodInvocation
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations
import org.springframework.security.authentication.AuthenticationTrustResolverImpl
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.provider.expression.OAuth2MethodSecurityExpressionHandler

// Expression Handler that check if have mgi enabled
class MgiEnabledCheckSEHandler(private val isMgiEnabled:Boolean) : OAuth2MethodSecurityExpressionHandler() {
    private val trustResolver = AuthenticationTrustResolverImpl()

    override fun createSecurityExpressionRoot(authentication: Authentication, invocation: MethodInvocation): MethodSecurityExpressionOperations {
        return MgiEnabledSecurityExpressionRoot(authentication, isMgiEnabled).apply {
            setPermissionEvaluator(permissionEvaluator)
            setTrustResolver(trustResolver)
            setRoleHierarchy(roleHierarchy)
        }
    }
}