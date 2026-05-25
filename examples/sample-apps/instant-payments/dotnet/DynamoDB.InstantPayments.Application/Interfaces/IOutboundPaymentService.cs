using DynamoDB.InstantPayments.Application.Models;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Application.Interfaces;

public interface IOutboundPaymentService
{
    Task<OutboundPaymentCreateResult> CreateAsync(
        CreateOutboundPaymentInput input,
        string idempotencyKey,
        CancellationToken cancellationToken);
}