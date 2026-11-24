using Application.Interfaces;
using Domain.Enums;
using Microsoft.AspNetCore.Mvc;

namespace PulseraPTR.Controllers;

public class EventosSosController : Controller
{
    private readonly IEventoEmergenciaService _eventos;
    private readonly IPacienteService _pacientes;

    public EventosSosController(IEventoEmergenciaService eventos, IPacienteService pacientes)
    {
        _eventos = eventos;
        _pacientes = pacientes;
    }

    public async Task<IActionResult> Index(int? pacienteId, bool? atendido, TipoEventoEmergencia? tipo, CancellationToken cancellationToken)
    {
        ViewBag.Pacientes = await _pacientes.ListarAsync(incluirInactivos: false, cancellationToken);
        ViewBag.PacienteId = pacienteId;
        ViewBag.Atendido = atendido;
        ViewBag.Tipo = tipo;

        var lista = await _eventos.ListarAsync(pacienteId, atendido, tipo, cancellationToken);
        return View(lista);
    }

    [HttpPost]
    [ValidateAntiForgeryToken]
    public async Task<IActionResult> MarcarAtendido(int id, CancellationToken cancellationToken)
    {
        await _eventos.MarcarAtendidoAsync(id, cancellationToken);
        return RedirectToAction(nameof(Index));
    }
}
