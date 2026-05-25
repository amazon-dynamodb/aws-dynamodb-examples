using DynamoDB.InstantPayments.Application.Validation;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;

namespace DynamoDB.InstantPayments.Api.Middleware;

public sealed class GlobalExceptionHandler(
    ILogger<GlobalExceptionHandler> logger,
    IProblemDetailsService problemDetailsService) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(
        HttpContext httpContext,
        Exception exception,
        CancellationToken cancellationToken)
    {
        var (statusCode, title) = exception switch
        {
            EntityValidationException => (StatusCodes.Status400BadRequest, "Validation error"),
            RequestHeaderValidationException => (StatusCodes.Status400BadRequest, "Request header validation error"),
            EntityNotFoundException => (StatusCodes.Status404NotFound, "Resource not found"),
            EntityConflictException => (StatusCodes.Status409Conflict, "Conflict"),
            IdempotencyConflictException => (StatusCodes.Status409Conflict, "Idempotency conflict"),
            _ => (StatusCodes.Status500InternalServerError, "Unexpected error")
        };

        logger.LogError(exception, "Request failed with status code {StatusCode}", statusCode);

        httpContext.Response.StatusCode = statusCode;

        return await problemDetailsService.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = httpContext,
            ProblemDetails = new ProblemDetails
            {
                Status = statusCode,
                Title = title,
                Detail = exception.Message,
                Instance = httpContext.Request.Path
            },
            Exception = exception
        });
    }
}