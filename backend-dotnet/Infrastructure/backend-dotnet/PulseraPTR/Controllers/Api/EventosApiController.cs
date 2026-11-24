using Application.Dtos;
using Application.Interfaces;
using Domain.Enums;
using Microsoft.AspNetCore.Mvc;

namespace PulseraPTR.Controllers.Api;

[ApiController]
[Route("api/eventos")]
public class EventosApiController : ControllerBase
{
    private readonly IEventoEmergenciaService _eventos;

    public EventosApiController(IEventoEmergenciaService eventos)
    {
        _eventos = eventos;
    }

    /// <summary>Registra un evento de emergencia (SOS manual, o CAIDA si se envía tipoEvento = "CAIDA").</summary>
    [HttpPost("sos")]
    public async Task<ActionResult<EventoEmergenciaDto>> PostSos([FromBody] CrearEventoEmergenciaDto dto, CancellationToken ct = default) =>
        Ok(await _eventos.RegistrarSosAsync(dto, ct));

    /// <summary>Registra una caída detectada automáticamente por la APK (fuerza tipoEvento = CAIDA).</summary>
    [HttpPost("caida")]
    public async Task<ActionResult<EventoEmergenciaDto>> PostCaida([FromBody] CrearEventoEmergenciaDto dto, CancellationToken ct = default)
    {
        dto.TipoEvento = TipoEventoEmergencia.CAIDA;
        return Ok(await _eventos.RegistrarSosAsync(dto, ct));
    }

    /// <summary>Lista eventos de emergencia; filtrar por tipo con ?tipo=SOS o ?tipo=CAIDA.</summary>
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<EventoEmergenciaDto>>> Get(
        [FromQuery] int? pacienteId,
        [FromQuery] bool? atendido,
        [FromQuery] TipoEventoEmergencia? tipo,
        CancellationToken ct = default) =>
        Ok(await _eventos.ListarAsync(pacienteId, atendido, tipo, ct));

    [HttpGet("paciente/{pacienteId:int}")]
    public async Task<ActionResult<IReadOnlyList<EventoEmergenciaDto>>> GetPorPaciente(int pacienteId, CancellationToken ct = default) =>
        Ok(await _eventos.ListarAsync(pacienteId, null, null, ct));

    [HttpPut("{id:int}/atender")]
    public async Task<IActionResult> Atender(int id, CancellationToken ct = default)
    {
        await _eventos.MarcarAtendidoAsync(id, ct);
        return NoContent();
    }
}
