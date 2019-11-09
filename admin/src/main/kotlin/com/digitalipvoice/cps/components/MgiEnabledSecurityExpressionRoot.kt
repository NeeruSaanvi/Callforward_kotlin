package com.digitalipvoice.cps.components

import org.springframework.security.access.expression.SecurityExpressionRoot
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations
import org.springframework.security.core.Authentication

class MgiEnabledSecurityExpressionRoot(authentication: Authentication, private val mgiEnabled: Boolean): SecurityExpressionRoot(authentication), MethodSecurityExpressionOperations {
    private var returnObject:Any? = null
    private var filterObject:Any? = null

    override fun getReturnObject(): Any? {
        return returnObject
    }

    override fun setReturnObject(p0: Any?) {
        returnObject = p0
    }

    override fun getFilterObject(): Any? {
        return filterObject
    }

    override fun setFilterObject(p0: Any?) {
        filterObject = p0
    }


    override fun getThis(): Any {
        return this
    }

    fun isMgiEnabled(): Boolean {
        return mgiEnabled
    }
}