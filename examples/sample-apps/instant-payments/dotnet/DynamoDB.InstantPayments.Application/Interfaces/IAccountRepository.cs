using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Application.Interfaces;

public interface IAccountRepository
{
    Task<bool> ExistsAsync(string accountId, CancellationToken cancellationToken);
}
