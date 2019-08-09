//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.authentication;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.google.GoogleCredentials;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;

/**
 * Google Authenticator.
 *
 * <p>This authenticator implements Google authentication using OpenId Connect on top of OAuth 2.0.
 *
 * <p>The google authenticator redirects unauthenticated requests to the google identity providers authorization endpoint
 * which will eventually redirect back to the {@link #_redirectUri} with an authCode which will be exchanged with
 * the google token_endpoint for an id_token. The request is then restored back to the original uri requested.
 * GoogleAuthentication uses {@link SessionAuthentication} to wrap Authentication results so that they
 * are  associated with the session.</p>
 */
public class GoogleAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = Log.getLogger(GoogleAuthenticator.class);
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";

    public static final String __USER_INFO = "org.eclipse.jetty.security.user_info";
    public static final String __CLIENT_ID = "org.eclipse.jetty.security.client_id";
    public static final String __REDIRECT_URI = "org.eclipse.jetty.security.redirect_uri";
    public static final String __ERROR_PAGE = "org.eclipse.jetty.security.error_page";
    public static final String __J_URI = "org.eclipse.jetty.security.google_URI";
    public static final String __J_POST = "org.eclipse.jetty.security.google_POST";
    public static final String __J_METHOD = "org.eclipse.jetty.security.google_METHOD";
    public static final String __CSRF_TOKEN = "org.eclipse.jetty.security.csrf_token";

    private String _clientId;
    private String _redirectUri;
    private String _errorPage;
    private String _errorPath;
    private boolean _alwaysSaveUri;

    public GoogleAuthenticator()
    {
    }

    public GoogleAuthenticator(String clientId, String redirectUri, String errorPage)
    {
        this._clientId = clientId;
        this._redirectUri = redirectUri;

        if (errorPage != null)
            setErrorPage(errorPage);
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        super.setConfiguration(configuration);

        String success = configuration.getInitParameter(__REDIRECT_URI);
        if (success != null)
            this._redirectUri = success;

        String error = configuration.getInitParameter(__ERROR_PAGE);
        if (error != null)
            setErrorPage(error);

        String clientId = configuration.getInitParameter(__CLIENT_ID);
        if (clientId != null)
            this._clientId = clientId;
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__GOOGLE_AUTH;
    }

    /**
     * If true, uris that cause a redirect to a login page will always
     * be remembered. If false, only the first uri that leads to a login
     * page redirect is remembered.
     * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=379909
     *
     * @param alwaysSave true to always save the uri
     */
    public void setAlwaysSaveUri(boolean alwaysSave)
    {
        _alwaysSaveUri = alwaysSave;
    }

    public boolean getAlwaysSaveUri()
    {
        return _alwaysSaveUri;
    }

    private void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _errorPath = null;
            _errorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("error-page must start with /");
                path = "/" + path;
            }
            _errorPage = path;
            _errorPath = path;

            if (_errorPath.indexOf('?') > 0)
                _errorPath = _errorPath.substring(0, _errorPath.indexOf('?'));
        }
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        UserIdentity user = super.login(username, credentials, request);
        if (user != null)
        {
            HttpSession session = ((HttpServletRequest)request).getSession();
            Authentication cached = new SessionAuthentication(getAuthMethod(), user, credentials);
            session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, cached);
            session.setAttribute(__USER_INFO, ((GoogleCredentials)credentials).getUserInfo());
        }
        return user;
    }

    @Override
    public void logout(ServletRequest request)
    {
        super.logout(request);
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);

        if (session == null)
            return;

        //clean up session
        session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
        session.removeAttribute(__USER_INFO);
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        //if this is a request resulting from a redirect after auth is complete
        //(ie its from a redirect to the original request uri) then due to
        //browser handling of 302 redirects, the method may not be the same as
        //that of the original request. Replace the method and original post
        //params (if it was a post).
        //
        //See Servlet Spec 3.1 sec 13.6.3
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);
        if (session == null || session.getAttribute(SessionAuthentication.__J_AUTHENTICATED) == null)
            return; //not authenticated yet

        String juri = (String)session.getAttribute(__J_URI);
        if (juri == null || juri.length() == 0)
            return; //no original uri saved

        String method = (String)session.getAttribute(__J_METHOD);
        if (method == null || method.length() == 0)
            return; //didn't save original request method

        StringBuffer buf = httpRequest.getRequestURL();
        if (httpRequest.getQueryString() != null)
            buf.append("?").append(httpRequest.getQueryString());

        if (!juri.equals(buf.toString()))
            return; //this request is not for the same url as the original

        //restore the original request's method on this request
        if (LOG.isDebugEnabled())
            LOG.debug("Restoring original method {} for {} with method {}", method, juri, httpRequest.getMethod());
        Request baseRequest = Request.getBaseRequest(request);
        baseRequest.setMethod(method);
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        final HttpServletRequest request = (HttpServletRequest)req;
        final HttpServletResponse response = (HttpServletResponse)res;
        final Request baseRequest = Request.getBaseRequest(request);
        final Response baseResponse = baseRequest.getResponse();

        mandatory |= hasAuthCode(request);
        if (!mandatory)
            return new DeferredAuthentication(this);

        if (isErrorPage(URIUtil.addPaths(request.getServletPath(), request.getPathInfo())) && !DeferredAuthentication.isDeferred(response))
            return new DeferredAuthentication(this);

        try
        {
            // Handle a request for authentication.
            if (hasAuthCode(request))
            {
                // Verify anti-forgery state token
                String antiForgeryToken = (String)request.getSession().getAttribute(__CSRF_TOKEN);
                if (antiForgeryToken == null || !antiForgeryToken.equals(request.getParameter("state")))
                {
                    LOG.warn("auth failed 403: invalid state parameter");
                    if (response != null)
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return Authentication.SEND_FAILURE;
                }

                // Attempt to login with the provided authCode
                GoogleCredentials credentials = new GoogleCredentials(request.getParameter("code"));
                UserIdentity user = login(null, credentials, request);
                HttpSession session = request.getSession(false);
                if (user != null)
                {
                    // Redirect to original request
                    String nuri;
                    synchronized (session)
                    {
                        nuri = (String)session.getAttribute(__J_URI);

                        if (nuri == null || nuri.length() == 0)
                        {
                            nuri = request.getContextPath();
                            if (nuri.length() == 0)
                                nuri = URIUtil.SLASH;
                        }
                    }
                    GoogleAuthentication googleAuth = new GoogleAuthentication(getAuthMethod(), user);
                    LOG.debug("authenticated {}->{}", googleAuth, nuri);

                    response.setContentLength(0);
                    int redirectCode = (baseRequest.getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion() ? HttpServletResponse.SC_MOVED_TEMPORARILY : HttpServletResponse.SC_SEE_OTHER);
                    baseResponse.sendRedirect(redirectCode, response.encodeRedirectURL(nuri));
                    return googleAuth;
                }

                // not authenticated
                if (LOG.isDebugEnabled())
                    LOG.debug("Google authentication FAILED");
                if (_errorPage == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth failed 403");
                    if (response != null)
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth failed {}", _errorPage);
                    int redirectCode = (baseRequest.getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion() ? HttpServletResponse.SC_MOVED_TEMPORARILY : HttpServletResponse.SC_SEE_OTHER);
                    baseResponse.sendRedirect(redirectCode, response.encodeRedirectURL(URIUtil.addPaths(request.getContextPath(), _errorPage)));
                }

                return Authentication.SEND_FAILURE;
            }

            // Look for cached authentication
            HttpSession session = request.getSession(false);
            Authentication authentication = session == null ? null : (Authentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
            if (authentication != null)
            {
                // Has authentication been revoked?
                if (authentication instanceof Authentication.User &&
                    _loginService != null &&
                    !_loginService.validate(((Authentication.User)authentication).getUserIdentity()))
                {
                    LOG.debug("auth revoked {}", authentication);
                    session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
                }
                else
                {
                    synchronized (session)
                    {
                        String jUri = (String)session.getAttribute(__J_URI);
                        if (jUri != null)
                        {
                            //check if the request is for the same url as the original and restore
                            //params if it was a post
                            LOG.debug("auth retry {}->{}", authentication, jUri);
                            StringBuffer buf = request.getRequestURL();
                            if (request.getQueryString() != null)
                                buf.append("?").append(request.getQueryString());

                            if (jUri.equals(buf.toString()))
                            {
                                MultiMap<String> jPost = (MultiMap<String>)session.getAttribute(__J_POST);
                                if (jPost != null)
                                {
                                    LOG.debug("auth rePOST {}->{}", authentication, jUri);
                                    baseRequest.setContentParameters(jPost);
                                }
                                session.removeAttribute(__J_URI);
                                session.removeAttribute(__J_METHOD);
                                session.removeAttribute(__J_POST);
                            }
                        }
                    }
                    LOG.debug("auth {}", authentication);
                    return authentication;
                }
            }


            // if we can't send challenge
            if (DeferredAuthentication.isDeferred(response))
            {
                LOG.debug("auth deferred {}", session == null ? null : session.getId());
                return Authentication.UNAUTHENTICATED;
            }

            // remember the current URI
            session = (session != null ? session : request.getSession(true));
            synchronized (session)
            {
                // But only if it is not set already, or we save every uri that leads to a login redirect
                if (session.getAttribute(__J_URI) == null || _alwaysSaveUri)
                {
                    StringBuffer buf = request.getRequestURL();
                    if (request.getQueryString() != null)
                        buf.append("?").append(request.getQueryString());
                    session.setAttribute(__J_URI, buf.toString());
                    session.setAttribute(__J_METHOD, request.getMethod());

                    if (MimeTypes.Type.FORM_ENCODED.is(req.getContentType()) && HttpMethod.POST.is(request.getMethod()))
                    {
                        MultiMap<String> formParameters = new MultiMap<>();
                        baseRequest.extractFormParameters(formParameters);
                        session.setAttribute(__J_POST, formParameters);
                    }
                }
            }

            // send the the challenge
            String challengeUri = getChallengeUri(session);
            LOG.debug("challenge {}->{}", session.getId(), challengeUri);
            int redirectCode = (baseRequest.getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion() ? HttpServletResponse.SC_MOVED_TEMPORARILY : HttpServletResponse.SC_SEE_OTHER);
            baseResponse.sendRedirect(redirectCode, response.encodeRedirectURL(challengeUri));

            return Authentication.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    public boolean hasAuthCode(HttpServletRequest request)
    {
        return request.getParameter("code") != null;
    }

    public boolean isErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_errorPath));
    }

    public String getChallengeUri(HttpSession session)
    {
        String antiForgeryToken;
        // TODO: is this synchronization necessary
        synchronized (session)
        {
            antiForgeryToken = (session.getAttribute(__CSRF_TOKEN) == null)
                ? new BigInteger(130, new SecureRandom()).toString(32)
                : (String)session.getAttribute(__CSRF_TOKEN);
            session.setAttribute(__CSRF_TOKEN, antiForgeryToken);
        }

        return AUTH_ENDPOINT +
            "?client_id=" + _clientId +
            "&redirect_uri=" + _redirectUri +
            "&scope=openid%20email%20profile" +
            "&state=" + antiForgeryToken +
            "&response_type=code";
    }

    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser)
    {
        return true;
    }

    /**
     * This Authentication represents a just completed Google authentication.
     * Subsequent requests from the same user are authenticated by the presents
     * of a {@link SessionAuthentication} instance in their session.
     */
    public static class GoogleAuthentication extends UserAuthentication implements Authentication.ResponseSent
    {
        public GoogleAuthentication(String method, UserIdentity userIdentity)
        {
            super(method, userIdentity);
        }

        @Override
        public String toString()
        {
            return "Google" + super.toString();
        }
    }
}