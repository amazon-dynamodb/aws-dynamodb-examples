using DynamoDB.InstantPayments.Application.Models;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Application.Interfaces;

public interface IOutboundPaymentRepository
{
    Task<bool> TryCreateIdempotentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken);

    Task<OutboundPayment> LoadReplayedPaymentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken);
}