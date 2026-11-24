using Application.Dtos;
using Domain.Enums;

namespace Application.Interfaces;

public interface IEventoEmergenciaService
{
    Task<EventoEmergenciaDto> RegistrarSosAsync(CrearEventoEmergenciaDto dto, CancellationToken cancellationToken = default);
    Task<IReadOnlyList<EventoEmergenciaDto>> ListarAsync(int? pacienteId, bool? atendido, TipoEventoEmergencia? tipo = null, CancellationToken cancellationToken = default);
    Task MarcarAtendidoAsync(int id, CancellationToken cancellationToken = default);
}
